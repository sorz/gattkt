# GattKt

Android library that convert `BluetoothGattCallback` to ergonomic Kotlin
coroutines API.

See also [sorz/blescanner](https://github.com/sorz/blescanner) for library
that scan BLE device with Kotlin coroutines.

Current support:

- [x] Connect to GATT server and discovery GATT services
- [x] Write an characteristic
- [x] Enable/disable notification/indication (with necessary descriptor set)
- [x] Read notification/indication value

PR is welcome if you need more functions.

## Install

- Add [JitPack](https://jitpack.io/) to your build file.
- Add `implementation 'com.github.sorz:gattkt:{VERSION}`

## Example

```kotlin
suspend fun handleAwesomeDevice(context: Context, device: BluetoothDevice) {
    val gattIo = device.connectGattIo(context)
    val service = gattIo.requireService(UUID_SOME_SERVICE)
    val char = service.getCharacteristic(UUID_SOME_CHAR)!!

    gattIo.enableNotification(char)
    gattIo.writeCharacteristic(char, byteArrayOf(...))
    val resp = gattIo.readCharacteristicChange(char)
    println("response: ${resp.contentToString()}")
    gattIo.disableNotificationOrIndication(char)

    gattIo.gatt.close()
}
```

Real-world example:
[MiBand.kt](https://github.com/sorz/miband4-export/blob/master/app/src/main/java/cn/edu/sustech/cse/miband/MiBand.kt)

## Debugging
Logs don't get printed by default. To enable logging, execute:

```bash
adb shell setprop log.tag.GattIo debug
```
