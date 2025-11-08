```
./gradlew assembleRelease
./gradlew assembleDebug

adb install -r -d ui/build/outputs/apk/release/ui-release-unsigned.apk
adb install -r -d ui/build/outputs/apk/release/ui-release.apk

adb logcat | grep WireGuard

# keytool -genkeypair -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
# keytool -genkeypair -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000  -alias wg-key

jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore my-release-key.jks ui/build/outputs/apk/release/ui-release-unsigned.apk wg-key

rm ui-release-signed.apk
zipalign -v 4 ui/build/outputs/apk/release/ui-release-unsigned.apk ui-release-signed.apk

adb install -r -d ui-release-signed.apk
```



## install android sdk
```
# Make a clean Linux SDK
mkdir -p $HOME/Android/Sdk
cd $HOME/Android/Sdk

# Download Commandline Tools for Linux
wget https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
unzip commandlinetools-linux-9477386_latest.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/* cmdline-tools/latest/

# Accept licenses and install required packages
yes | ./cmdline-tools/latest/bin/sdkmanager --licenses
./cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "cmake;3.22.1" "ndk;27.0.12077973"

export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

### fix NDK
```
rm -rf /home/hunter/Android/Sdk/ndk/27.0.12077973

cd $ANDROID_HOME/cmdline-tools/latest/bin
./sdkmanager "ndk;27.0.12077973"
```


## Debug

```
# Check what's in the APK
aapt dump badging ui/build/outputs/apk/release/ui-release-unsigned.apk | head -20

# Or if you have apkanalyzer:
# apkanalyzer apk summary ui/build/outputs/apk/release/ui-release-unsigned.apk
```


```
aapt dump badging ui/build/outputs/apk/release/ui-release-unsigned.apk | grep native-code
```

### ADB over Network (If same WiFi)
On your Xiaomi device: Enable "Wireless debugging" in Developer Options
Note the IP address and port
In WSL: 
```
adb pair <ip>:<port>
adb connect <ip>:<port>
```

```
adb kill-server
adb start-server
```

