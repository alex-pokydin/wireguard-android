/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.BR
import com.wireguard.android.util.ConnectivityChecker
import com.wireguard.android.util.QuantityFormatter
import kotlinx.coroutines.*
import android.util.Log

/**
 * Enhanced tunnel connectivity information that tracks detailed VPN status
 */
class TunnelConnectivity : BaseObservable() {
    
    @get:Bindable
    var connectivityStatus: ConnectivityChecker.ConnectivityStatus? = null
        private set
        
    @get:Bindable
    var connectionState: ConnectivityChecker.ConnectionState = ConnectivityChecker.ConnectionState.DISCONNECTED
        private set
        
    @get:Bindable
    var lastUpdateTime: Long = 0
        private set
        
    @get:Bindable
    var isMonitoring: Boolean = false
        private set
    
    private var monitoringJob: Job? = null
    private var connectivityChecker: ConnectivityChecker? = null
    private var parentNotifier: (() -> Unit)? = null
    private var timeUpdateJob: Job? = null
    
    companion object {
        private const val TAG = "WireGuard/TunnelConnectivity"
        private const val MONITORING_INTERVAL_MS = 15000L // Check every 15 seconds
        private const val FAST_MONITORING_INTERVAL_MS = 5000L // Fast checking when connecting
    }
    
    /**
     * Start monitoring connectivity for this tunnel
     */
    fun startMonitoring(checker: ConnectivityChecker, coroutineScope: CoroutineScope, parentNotifier: (() -> Unit)? = null) {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring connectivity")
            return
        }
        
        connectivityChecker = checker
        this.parentNotifier = parentNotifier
        isMonitoring = true
        // Don't notify property change here to avoid crashes during initial build
        
        monitoringJob = coroutineScope.launch {
            // Do an immediate check when monitoring starts
            try {
                Log.d(TAG, "Starting initial connectivity check")
                val status = checker.checkConnectivity()
                updateConnectivityStatus(status)
            } catch (e: Exception) {
                Log.e(TAG, "Initial connectivity check failed", e)
            }
            
            while (isActive && isMonitoring) {
                try {
                    val status = checker.checkConnectivity()
                    updateConnectivityStatus(status)
                    
                    // Use faster checking when in transitional states
                    val interval = when (connectionState) {
                        ConnectivityChecker.ConnectionState.CONNECTING,
                        ConnectivityChecker.ConnectionState.ISSUES -> FAST_MONITORING_INTERVAL_MS
                        else -> MONITORING_INTERVAL_MS
                    }
                    
                    delay(interval)
                } catch (e: Exception) {
                    Log.e(TAG, "Connectivity monitoring error", e)
                    delay(MONITORING_INTERVAL_MS)
                }
            }
        }
        
        Log.d(TAG, "Started connectivity monitoring")
        
        // Start periodic time updates for relative time display
        startTimeUpdates(coroutineScope)
    }
    
    private fun startTimeUpdates(coroutineScope: CoroutineScope) {
        timeUpdateJob = coroutineScope.launch {
            while (isActive && isMonitoring) {
                delay(1000) // Update every second (like latest handshake)
                if (lastUpdateTime > 0) {
                    notifyPropertyChanged(BR.formattedLastCheckTime)
                    parentNotifier?.invoke()
                }
            }
        }
    }
    
    /**
     * Stop monitoring connectivity
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        isMonitoring = false
        // Don't notify property change here to avoid crashes during initial build
        
        monitoringJob?.cancel()
        monitoringJob = null
        timeUpdateJob?.cancel()
        timeUpdateJob = null
        parentNotifier = null
        
        // Reset to disconnected state
        updateConnectionState(ConnectivityChecker.ConnectionState.DISCONNECTED)
        
        Log.d(TAG, "Stopped connectivity monitoring")
    }
    
    /**
     * Trigger an immediate connectivity check
     */
    suspend fun checkNow(): ConnectivityChecker.ConnectivityStatus? {
        val checker = connectivityChecker ?: return null
        
        return try {
            val status = checker.checkConnectivity()
            updateConnectivityStatus(status)
            status
        } catch (e: Exception) {
            Log.e(TAG, "Manual connectivity check failed", e)
            null
        }
    }
    
    private fun updateConnectivityStatus(status: ConnectivityChecker.ConnectivityStatus) {
        connectivityStatus = status
        updateConnectionState(status.getOverallStatus())
        lastUpdateTime = System.currentTimeMillis()
        
        notifyPropertyChanged(BR.connectivityStatus)
        notifyPropertyChanged(BR.lastUpdateTime)
        
        // Notify that all derived properties have changed
        notifyPropertyChanged(BR.statusMessage)
        notifyPropertyChanged(BR.detailedStatus)
        notifyPropertyChanged(BR.healthy)
        notifyPropertyChanged(BR.warnings)
        notifyPropertyChanged(BR.statusColor)
        notifyPropertyChanged(BR.formattedLastCheckTime)
        
        // Notify parent that connectivity has changed
        parentNotifier?.invoke()
        
        Log.d(TAG, "Connectivity status updated: ${connectionState.name}")
    }
    
    private fun updateConnectionState(newState: ConnectivityChecker.ConnectionState) {
        if (connectionState != newState) {
            connectionState = newState
            notifyPropertyChanged(BR.connectionState)
            
            // Notify that all derived properties have changed when state changes
            notifyPropertyChanged(BR.statusMessage)
            notifyPropertyChanged(BR.detailedStatus)
            notifyPropertyChanged(BR.healthy)
            notifyPropertyChanged(BR.warnings)
            notifyPropertyChanged(BR.statusColor)
            notifyPropertyChanged(BR.formattedLastCheckTime)
            
            // Notify parent that connectivity has changed
            parentNotifier?.invoke()
        }
    }
    
    /**
     * Set the tunnel as connecting (used when tunnel state changes to UP)
     */
    fun setConnecting() {
        updateConnectionState(ConnectivityChecker.ConnectionState.CONNECTING)
    }
    
    /**
     * Get a human-readable status message
     */
    @Bindable
    fun getStatusMessage(): String {
        val status = connectivityStatus
        return when (connectionState) {
            ConnectivityChecker.ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectivityChecker.ConnectionState.CONNECTING -> "Connecting..."
            ConnectivityChecker.ConnectionState.CONNECTED -> {
                if (status != null && status.latencyMs > 0) {
                    "Connected (${status.latencyMs}ms)"
                } else {
                    "Connected"
                }
            }
            ConnectivityChecker.ConnectionState.ISSUES -> {
                status?.errorMessage ?: "Connection issues"
            }
            ConnectivityChecker.ConnectionState.BLOCKED -> {
                status?.errorMessage ?: "VPN may be blocked by ISP"
            }
            ConnectivityChecker.ConnectionState.NO_INTERNET -> "No internet access"
        }
    }
    
    /**
     * Get detailed status for UI display
     */
    @Bindable
    fun getDetailedStatus(): String {
        val status = connectivityStatus ?: return getStatusMessage()
        
        val details = mutableListOf<String>()
        
        if (status.hasInternetAccess) {
            details.add("Internet: ✓")
        } else {
            details.add("Internet: ✗")
        }
        
        if (status.vpnTrafficWorking) {
            details.add("VPN: ✓")
        } else {
            details.add("VPN: ✗")
        }
        
        if (status.isPotentiallyBlocked) {
            details.add("Blocked: ⚠")
        }
        
        if (status.latencyMs > 0) {
            details.add("${status.latencyMs}ms")
        }
        
        return details.joinToString(" • ")
    }
    
    /**
     * Check if the connection appears to be working well
     */
    @Bindable
    fun isHealthy(): Boolean {
        return connectionState == ConnectivityChecker.ConnectionState.CONNECTED
    }
    
    /**
     * Check if there are any warning signs
     */
    @Bindable
    fun isWarnings(): Boolean {
        return connectionState in listOf(
            ConnectivityChecker.ConnectionState.ISSUES,
            ConnectivityChecker.ConnectionState.BLOCKED,
            ConnectivityChecker.ConnectionState.NO_INTERNET
        )
    }
    
    /**
     * Get the appropriate color for status indication
     */
    @Bindable
    fun getStatusColor(): Int {
        return when (connectionState) {
            ConnectivityChecker.ConnectionState.CONNECTED -> 0xFF4CAF50.toInt() // Green
            ConnectivityChecker.ConnectionState.CONNECTING -> 0xFFFF9800.toInt() // Orange
            ConnectivityChecker.ConnectionState.ISSUES -> 0xFFFF5722.toInt() // Red-Orange
            ConnectivityChecker.ConnectionState.BLOCKED -> 0xFFF44336.toInt() // Red
            ConnectivityChecker.ConnectionState.NO_INTERNET -> 0xFF9E9E9E.toInt() // Grey
            ConnectivityChecker.ConnectionState.DISCONNECTED -> 0xFF616161.toInt() // Dark Grey
        }
    }
    
    /**
     * Get formatted last check time (similar to latest handshake)
     */
    @Bindable
    fun getFormattedLastCheckTime(): String {
        if (lastUpdateTime == 0L) return ""
        return QuantityFormatter.formatEpochAgo(lastUpdateTime)
    }
}
