/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import com.wireguard.android.backend.BackendException.Reason;
import com.wireguard.android.backend.Tunnel.State;
import com.wireguard.android.util.SharedLibraryLoader;
import com.wireguard.config.Config;
import com.wireguard.config.InetEndpoint;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Peer;
import com.wireguard.crypto.Key;
import com.wireguard.crypto.KeyFormatException;
import com.wireguard.util.NonNullForAll;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

/**
 * Implementation of {@link Backend} that uses the wireguard-go userspace implementation to provide
 * WireGuard tunnels.
 */
@NonNullForAll
public final class GoBackend implements Backend {
    private static final int DNS_RESOLUTION_RETRIES = 10;
    private static final String TAG = "WireGuard/GoBackend";
    @Nullable private static AlwaysOnCallback alwaysOnCallback;
    private static GhettoCompletableFuture<VpnService> vpnService = new GhettoCompletableFuture<>();
    private final Context context;
    @Nullable private Config currentConfig;
    @Nullable private Tunnel currentTunnel;
    private int currentTunnelHandle = -1;

    /**
     * Public constructor for GoBackend.
     *
     * @param context An Android {@link Context}
     */
    public GoBackend(final Context context) {
        SharedLibraryLoader.loadSharedLibrary(context, "wg-go");
        this.context = context;
    }

    /**
     * Set a {@link AlwaysOnCallback} to be invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     *
     * @param cb Callback to be invoked
     */
    public static void setAlwaysOnCallback(final AlwaysOnCallback cb) {
        alwaysOnCallback = cb;
    }

    @Nullable private static native String wgGetConfig(int handle);

    private static native int wgGetSocketV4(int handle);

    private static native int wgGetSocketV6(int handle);

    private static native void wgTurnOff(int handle);

    private static native int wgTurnOn(String ifName, int tunFd, String settings);

    private static native String wgVersion();

    /**
     * Sends multiple random UDP packets to bypass DPI detection.
     * This implements enhanced workaround from https://gist.github.com/httpsx/76a98ea28e6f3a4ffc947e768c0b6c01
     * 
     * Sends 6 packets of random data with varying sizes (128-1400 bytes) to evade DPI.
     * Pure random data is impossible for DPI systems to fingerprint or block.
     * 
     * @param config The WireGuard configuration
     * @param service The VPN service for socket protection
     * @throws Exception if the UDP packet cannot be sent
     */
    private void sendDpiBypassPacket(final Config config, final VpnService service) throws Exception {
        if (!config.getInterface().getDpiBypass()) {
            return; // DPI bypass is not enabled
        }

        final Optional<Integer> listenPortOpt = config.getInterface().getListenPort();
        if (!listenPortOpt.isPresent()) {
            Log.w(TAG, "DPI bypass enabled but no ListenPort specified - skipping");
            return;
        }

        final int listenPort = listenPortOpt.get();
        
        // Generate random payloads of varying sizes (aggressive approach for maximum reliability)
        // Sizes: 128, 256, 512, 768, 1024, 1400 bytes (near-MTU)
        final byte[][] payloadPatterns = new byte[][] {
            generateRandomPayload(128),
            generateRandomPayload(256),
            generateRandomPayload(512),
            generateRandomPayload(768),
            generateRandomPayload(1024),
            generateRandomPayload(1400)  // Near MTU size
        };
        
        final int burstDelayMs = 10;  // Delay between packets
        final int postBypassDelayMs = 150;  // Delay before WireGuard handshake
        
        // Send packets to each peer endpoint
        for (final Peer peer : config.getPeers()) {
            final InetEndpoint endpoint = peer.getEndpoint().orElse(null);
            if (endpoint == null) {
                continue;
            }

            final InetAddress endpointAddress;
            try {
                final Optional<InetEndpoint> resolvedEndpoint = endpoint.getResolved();
                if (resolvedEndpoint.isPresent()) {
                    endpointAddress = InetAddress.getByName(resolvedEndpoint.get().getHost());
                } else {
                    endpointAddress = null;
                }
            } catch (final Exception e) {
                Log.w(TAG, "Failed to resolve endpoint for DPI bypass: " + e.getMessage());
                continue;
            }
            
            if (endpointAddress == null) {
                Log.w(TAG, "Could not resolve endpoint for DPI bypass: " + endpoint.getHost());
                continue;
            }

            final int endpointPort = endpoint.getPort();
            
            // Try multiple port binding strategies for maximum reliability
            boolean success = false;
            
            // Strategy 1: Use configured ListenPort
            success = sendDpiPacketBurst(endpointAddress, endpointPort, listenPort, 
                                        payloadPatterns, burstDelayMs, service, true);
            
            // Strategy 2: Try with random high port if Strategy 1 fails
            if (!success) {
                final int randomPort = 49152 + (int)(Math.random() * 10000);
                success = sendDpiPacketBurst(endpointAddress, endpointPort, randomPort, 
                                            payloadPatterns, burstDelayMs, service, false);
            }
            
            // Strategy 3: Try without binding to specific port as last resort
            if (!success) {
                success = sendDpiPacketBurst(endpointAddress, endpointPort, 0, 
                                            payloadPatterns, burstDelayMs, service, false);
            }
            
            if (success) {
                Log.i(TAG, "DPI bypass: sent " + payloadPatterns.length + " random packets to " + 
                          endpointAddress.getHostAddress() + ":" + endpointPort);
                
                // Wait before starting WireGuard handshake to ensure packets arrive first
                try {
                    Thread.sleep(postBypassDelayMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                Log.w(TAG, "DPI bypass failed for " + endpoint.getHost() + " (will try WireGuard anyway)");
            }
        }
    }
    
    /**
     * Generates random payload for DPI evasion
     * 
     * @param size Size of the payload
     * @return Random byte array
     */
    private byte[] generateRandomPayload(final int size) {
        final byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte)(Math.random() * 256);
        }
        return payload;
    }
    
    /**
     * Sends a burst of UDP packets with random payloads
     * 
     * @param address Target address
     * @param port Target port
     * @param sourcePort Source port (0 for system-assigned)
     * @param payloads Array of random payloads to send
     * @param delayMs Delay between packets
     * @param service VPN service for socket protection
     * @param requireExactPort Whether binding to exact port is required
     * @return true if packets were sent successfully
     */
    private boolean sendDpiPacketBurst(final InetAddress address, final int port, 
                                      final int sourcePort, final byte[][] payloads,
                                      final int delayMs, final VpnService service, 
                                      final boolean requireExactPort) {
        DatagramSocket socket = null;
        try {
            // Create socket with or without specific port
            if (sourcePort > 0) {
                try {
                    socket = new DatagramSocket(sourcePort);
                } catch (final Exception e) {
                    if (requireExactPort) {
                        Log.w(TAG, "Failed to bind to port " + sourcePort + ": " + e.getMessage());
                        return false;
                    }
                    // Fall back to system-assigned port
                    socket = new DatagramSocket();
                }
            } else {
                socket = new DatagramSocket();
            }
            
            // Protect socket through VPN service to ensure it goes through physical interface
            if (service != null) {
                service.protect(socket);
            }
            
            // Set socket options for better reliability
            socket.setSoTimeout(5000);
            socket.setTrafficClass(0x04); // IPTOS_RELIABILITY
            
            // Send all random payloads
            int successCount = 0;
            int totalBytes = 0;
            for (int i = 0; i < payloads.length; i++) {
                final byte[] payload = payloads[i];
                try {
                    final DatagramPacket packet = new DatagramPacket(
                        payload, payload.length, address, port
                    );
                    socket.send(packet);
                    successCount++;
                    totalBytes += payload.length;
                    
                    // Small delay between packets (except after last one)
                    if (delayMs > 0 && i < payloads.length - 1) {
                        Thread.sleep(delayMs);
                    }
                } catch (final Exception e) {
                    Log.w(TAG, "Failed to send packet " + (i+1) + ": " + e.getMessage());
                }
            }
            
            Log.d(TAG, "Sent " + successCount + "/" + payloads.length + 
                      " DPI bypass packets (" + totalBytes + " bytes)");
            return successCount > 0;
            
        } catch (final Exception e) {
            Log.w(TAG, "DPI bypass burst failed: " + e.getMessage());
            return false;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
    

    /**
     * Method to get the names of running tunnels.
     *
     * @return A set of string values denoting names of running tunnels.
     */
    @Override
    public Set<String> getRunningTunnelNames() {
        if (currentTunnel != null) {
            final Set<String> runningTunnels = new ArraySet<>();
            runningTunnels.add(currentTunnel.getName());
            return runningTunnels;
        }
        return Collections.emptySet();
    }

    /**
     * Get the associated {@link State} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to examine the state of.
     * @return {@link State} associated with the given tunnel.
     */
    @Override
    public State getState(final Tunnel tunnel) {
        return currentTunnel == tunnel ? State.UP : State.DOWN;
    }

    /**
     * Get the associated {@link Statistics} for a given {@link Tunnel}.
     *
     * @param tunnel The tunnel to retrieve statistics for.
     * @return {@link Statistics} associated with the given tunnel.
     */
    @Override
    public Statistics getStatistics(final Tunnel tunnel) {
        final Statistics stats = new Statistics();
        if (tunnel != currentTunnel || currentTunnelHandle == -1)
            return stats;
        final String config = wgGetConfig(currentTunnelHandle);
        if (config == null)
            return stats;
        Key key = null;
        long rx = 0;
        long tx = 0;
        long latestHandshakeMSec = 0;
        for (final String line : config.split("\\n")) {
            if (line.startsWith("public_key=")) {
                if (key != null)
                    stats.add(key, rx, tx, latestHandshakeMSec);
                rx = 0;
                tx = 0;
                latestHandshakeMSec = 0;
                try {
                    key = Key.fromHex(line.substring(11));
                } catch (final KeyFormatException ignored) {
                    key = null;
                }
            } else if (line.startsWith("rx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    rx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    rx = 0;
                }
            } else if (line.startsWith("tx_bytes=")) {
                if (key == null)
                    continue;
                try {
                    tx = Long.parseLong(line.substring(9));
                } catch (final NumberFormatException ignored) {
                    tx = 0;
                }
            } else if (line.startsWith("last_handshake_time_sec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(24)) * 1000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            } else if (line.startsWith("last_handshake_time_nsec=")) {
                if (key == null)
                    continue;
                try {
                    latestHandshakeMSec += Long.parseLong(line.substring(25)) / 1000000;
                } catch (final NumberFormatException ignored) {
                    latestHandshakeMSec = 0;
                }
            }
        }
        if (key != null)
            stats.add(key, rx, tx, latestHandshakeMSec);
        return stats;
    }

    /**
     * Get the version of the underlying wireguard-go library.
     *
     * @return {@link String} value of the version of the wireguard-go library.
     */
    @Override
    public String getVersion() {
        return wgVersion();
    }

    /**
     * Change the state of a given {@link Tunnel}, optionally applying a given {@link Config}.
     *
     * @param tunnel The tunnel to control the state of.
     * @param state  The new state for this tunnel. Must be {@code UP}, {@code DOWN}, or
     *               {@code TOGGLE}.
     * @param config The configuration for this tunnel, may be null if state is {@code DOWN}.
     * @return {@link State} of the tunnel after state changes are applied.
     * @throws Exception Exception raised while changing tunnel state.
     */
    @Override
    public State setState(final Tunnel tunnel, State state, @Nullable final Config config) throws Exception {
        final State originalState = getState(tunnel);

        if (state == State.TOGGLE)
            state = originalState == State.UP ? State.DOWN : State.UP;
        if (state == originalState && tunnel == currentTunnel && config == currentConfig)
            return originalState;
        if (state == State.UP) {
            final Config originalConfig = currentConfig;
            final Tunnel originalTunnel = currentTunnel;
            if (currentTunnel != null)
                setStateInternal(currentTunnel, null, State.DOWN);
            try {
                setStateInternal(tunnel, config, state);
            } catch (final Exception e) {
                if (originalTunnel != null)
                    setStateInternal(originalTunnel, originalConfig, State.UP);
                throw e;
            }
        } else if (state == State.DOWN && tunnel == currentTunnel) {
            setStateInternal(tunnel, null, State.DOWN);
        }
        return getState(tunnel);
    }

    private void setStateInternal(final Tunnel tunnel, @Nullable final Config config, final State state)
            throws Exception {
        Log.i(TAG, "Bringing tunnel " + tunnel.getName() + ' ' + state);

        if (state == State.UP) {
            if (config == null)
                throw new BackendException(Reason.TUNNEL_MISSING_CONFIG);

            if (VpnService.prepare(context) != null)
                throw new BackendException(Reason.VPN_NOT_AUTHORIZED);

            final VpnService service;
            if (!vpnService.isDone()) {
                Log.d(TAG, "Requesting to start VpnService");
                context.startService(new Intent(context, VpnService.class));
            }

            try {
                service = vpnService.get(2, TimeUnit.SECONDS);
            } catch (final TimeoutException e) {
                final Exception be = new BackendException(Reason.UNABLE_TO_START_VPN);
                be.initCause(e);
                throw be;
            }
            service.setOwner(this);

            if (currentTunnelHandle != -1) {
                Log.w(TAG, "Tunnel already up");
                return;
            }


            dnsRetry: for (int i = 0; i < DNS_RESOLUTION_RETRIES; ++i) {
                // Pre-resolve IPs so they're cached when building the userspace string
                for (final Peer peer : config.getPeers()) {
                    final InetEndpoint ep = peer.getEndpoint().orElse(null);
                    if (ep == null)
                        continue;
                    if (ep.getResolved().orElse(null) == null) {
                        if (i < DNS_RESOLUTION_RETRIES - 1) {
                            Log.w(TAG, "DNS host \"" + ep.getHost() + "\" failed to resolve; trying again");
                            Thread.sleep(1000);
                            continue dnsRetry;
                        } else
                            throw new BackendException(Reason.DNS_RESOLUTION_FAILURE, ep.getHost());
                    }
                }
                break;
            }

            // Build config
            final String goConfig = config.toWgUserspaceString();

            // Send DPI bypass packet if enabled (always uses Moderate mode with smart defaults)
            try {
                sendDpiBypassPacket(config, service);
            } catch (final Exception e) {
                Log.w(TAG, "DPI bypass failed but continuing with connection: " + e.getMessage());
            }

            // Create the vpn tunnel with android API
            final VpnService.Builder builder = service.getBuilder();
            builder.setSession(tunnel.getName());

            for (final String excludedApplication : config.getInterface().getExcludedApplications())
                builder.addDisallowedApplication(excludedApplication);

            for (final String includedApplication : config.getInterface().getIncludedApplications())
                builder.addAllowedApplication(includedApplication);

            for (final InetNetwork addr : config.getInterface().getAddresses())
                builder.addAddress(addr.getAddress(), addr.getMask());

            for (final InetAddress addr : config.getInterface().getDnsServers())
                builder.addDnsServer(addr.getHostAddress());

            for (final String dnsSearchDomain : config.getInterface().getDnsSearchDomains())
                builder.addSearchDomain(dnsSearchDomain);

            boolean sawDefaultRoute = false;
            for (final Peer peer : config.getPeers()) {
                for (final InetNetwork addr : peer.getAllowedIps()) {
                    if (addr.getMask() == 0)
                        sawDefaultRoute = true;
                    builder.addRoute(addr.getAddress(), addr.getMask());
                }
            }

            // "Kill-switch" semantics
            if (!(sawDefaultRoute && config.getPeers().size() == 1)) {
                builder.allowFamily(OsConstants.AF_INET);
                builder.allowFamily(OsConstants.AF_INET6);
            }

            builder.setMtu(config.getInterface().getMtu().orElse(1280));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                builder.setMetered(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                service.setUnderlyingNetworks(null);

            builder.setBlocking(true);
            try (final ParcelFileDescriptor tun = builder.establish()) {
                if (tun == null)
                    throw new BackendException(Reason.TUN_CREATION_ERROR);
                Log.d(TAG, "Go backend " + wgVersion());
                currentTunnelHandle = wgTurnOn(tunnel.getName(), tun.detachFd(), goConfig);
            }
            if (currentTunnelHandle < 0)
                throw new BackendException(Reason.GO_ACTIVATION_ERROR_CODE, currentTunnelHandle);

            currentTunnel = tunnel;
            currentConfig = config;

            service.protect(wgGetSocketV4(currentTunnelHandle));
            service.protect(wgGetSocketV6(currentTunnelHandle));
        } else {
            if (currentTunnelHandle == -1) {
                Log.w(TAG, "Tunnel already down");
                return;
            }
            int handleToClose = currentTunnelHandle;
            currentTunnel = null;
            currentTunnelHandle = -1;
            currentConfig = null;
            wgTurnOff(handleToClose);
            try {
                vpnService.get(0, TimeUnit.NANOSECONDS).stopSelf();
            } catch (final TimeoutException ignored) { }
        }

        tunnel.onStateChange(state);
    }

    /**
     * Callback for {@link GoBackend} that is invoked when {@link VpnService} is started by the
     * system's Always-On VPN mode.
     */
    public interface AlwaysOnCallback {
        void alwaysOnTriggered();
    }

    // TODO: When we finally drop API 21 and move to API 24, delete this and replace with the ordinary CompletableFuture.
    private static final class GhettoCompletableFuture<V> {
        private final LinkedBlockingQueue<V> completion = new LinkedBlockingQueue<>(1);
        private final FutureTask<V> result = new FutureTask<>(completion::peek);

        public boolean complete(final V value) {
            final boolean offered = completion.offer(value);
            if (offered)
                result.run();
            return offered;
        }

        public V get() throws ExecutionException, InterruptedException {
            return result.get();
        }

        public V get(final long timeout, final TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
            return result.get(timeout, unit);
        }

        public boolean isDone() {
            return !completion.isEmpty();
        }

        public GhettoCompletableFuture<V> newIncompleteFuture() {
            return new GhettoCompletableFuture<>();
        }
    }

    /**
     * {@link android.net.VpnService} implementation for {@link GoBackend}
     */
    public static class VpnService extends android.net.VpnService {
        @Nullable private GoBackend owner;

        public Builder getBuilder() {
            return new Builder();
        }

        @Override
        public void onCreate() {
            vpnService.complete(this);
            super.onCreate();
        }

        @Override
        public void onDestroy() {
            if (owner != null) {
                final Tunnel tunnel = owner.currentTunnel;
                if (tunnel != null) {
                    if (owner.currentTunnelHandle != -1)
                        wgTurnOff(owner.currentTunnelHandle);
                    owner.currentTunnel = null;
                    owner.currentTunnelHandle = -1;
                    owner.currentConfig = null;
                    tunnel.onStateChange(State.DOWN);
                }
            }
            vpnService = vpnService.newIncompleteFuture();
            super.onDestroy();
        }

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            vpnService.complete(this);
            if (intent == null || intent.getComponent() == null || !intent.getComponent().getPackageName().equals(getPackageName())) {
                Log.d(TAG, "Service started by Always-on VPN feature");
                if (alwaysOnCallback != null)
                    alwaysOnCallback.alwaysOnTriggered();
            }
            return super.onStartCommand(intent, flags, startId);
        }

        public void setOwner(final GoBackend owner) {
            this.owner = owner;
        }
    }
}
