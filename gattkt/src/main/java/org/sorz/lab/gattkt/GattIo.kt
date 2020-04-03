package org.sorz.lab.gattkt

import android.bluetooth.*
import android.content.Context
import androidx.collection.CircularArray
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.warn
import java.io.IOException
import java.lang.IllegalStateException
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


private val UUID_CLIENT_CHAR_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Bluetooth GATT helper to write characteristic and read notification/indication.
 */
class GattIo internal constructor(
    private val context: Context,
    val device: BluetoothDevice
) : AnkoLogger {
    lateinit var gatt: BluetoothGatt
    private var connectContinuation: Continuation<Unit>? = null
    private var charWriteCont: MutableMap<UUID, Continuation<Unit>> = mutableMapOf()
    private var descWriteCont: MutableMap<Pair<UUID, UUID>, Continuation<Unit>> = mutableMapOf()
    private var charChangeCont: MutableMap<UUID, Continuation<ByteArray>> = mutableMapOf()
    private var charChangeQueue: MutableMap<UUID, CircularArray<ByteArray>> = mutableMapOf()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            this@GattIo.gatt = gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    debug { "GATT connected, discover services" }
                    if (!gatt.discoverServices())
                        connectContinuation
                            ?.resumeWithException(IOException("fail to discover services"))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val e = IOException("GATT disconnected")
                    connectContinuation?.resumeWithException(e)
                    charWriteCont.values.forEach { it.resumeWithException(e) }
                    descWriteCont.values.forEach { it.resumeWithException(e) }
                    charChangeCont.values.forEach { it.resumeWithException(e) }
                }
                else -> error("Unknown GATT state: $newState")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            debug("service discovered ${gatt.services}")
            connectContinuation?.resume(Unit)
            connectContinuation = null
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            debug {
                "descriptor WRITTEN ${descriptor.characteristic.uuid} $status " +
                        "${descriptor.value?.contentToString()}"
            }
            val key = Pair(descriptor.characteristic.uuid, descriptor.uuid)
            val cont = descWriteCont.remove(key)
            if (cont == null) {
                warn("descriptor $descriptor not found")
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS)
                cont.resumeWithException(IOException("Fail to write descriptor: $status"))
            else
                cont.resume(Unit)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic, status: Int
        ) {
            debug { "characteristic WRITTEN ${char.uuid} $status ${char.value?.contentToString()}" }
            val cont = charWriteCont.remove(char.uuid)
            if (cont == null) {
                warn("characteristic $char not found")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS)
                cont.resumeWithException(IOException("Fail to write characteristic: $status"))
            else
                cont.resume(Unit)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic
        ) {
            debug { "characteristic CHANGED ${char.uuid} ${char.value?.contentToString()}" }
            charChangeCont.remove(char.uuid)?.resume(char.value)
                ?: charChangeQueue.getOrPut(char.uuid) { CircularArray() }.addLast(char.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            status: Int
        ) {
            debug("characteristic READ ${char.uuid} ${char.value?.contentToString()}")
        }
    }

    internal suspend fun connect() {
        if (connectContinuation != null) throw IllegalStateException("repeated invoking connect()")
        suspendCoroutine { cont: Continuation<Unit> ->
            connectContinuation = cont
            debug { "connecting to $device" }
            device.connectGatt(context, true, gattCallback)
        }
        debug { "$device connected" }
    }

    /**
     * Enable notification. This turn on both client-side (Android's) notification and set
     * 0x2902 descriptor to enable notification on GATT server.
     */
    suspend fun enableNotification(char: BluetoothGattCharacteristic) {
        setNotification(char, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, true)
    }

    /**
     * Enable indication. This turn on both client-side (Android's) notification and set
     * 0x2902 descriptor to enable indication on GATT server.
     */
    suspend fun enableIndication(char: BluetoothGattCharacteristic) {
        setNotification(char, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, true)
    }

    /**
     * Disable notification and/or indication on both client and GATT server.
     */
    suspend fun disableNotificationOrIndication(char: BluetoothGattCharacteristic) {
        setNotification(char, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, false)
    }

    private suspend fun setNotification(
        char: BluetoothGattCharacteristic,
        descValue: ByteArray,
        enable: Boolean
    ) {
        val desc = char.getDescriptor(UUID_CLIENT_CHAR_CONFIG)
            ?: throw IOException("missing config descriptor on $char")
        val key = Pair(char.uuid, desc.uuid)
        if (descWriteCont.containsKey(key))
            throw IllegalStateException("last setNotification() not finish")

        if (!gatt.setCharacteristicNotification(char, enable))
            throw IOException("fail to set notification on $char")

        return suspendCoroutine { cont ->
            descWriteCont[key] = cont
            desc.value = descValue
            if (!gatt.writeDescriptor(desc))
                cont.resumeWithException(IOException("fail to config descriptor $this"))
        }
    }

    /**
     * Write value to the characteristic. [char.value] will be set to [value] if it's not null.
     */
    suspend fun writeCharacteristic(char: BluetoothGattCharacteristic, value: ByteArray? = null) {
        if (charWriteCont.containsKey(char.uuid))
            throw IllegalStateException("last writeCharacteristic() not finish")
        return try {
            suspendCancellableCoroutine { cont ->
                charWriteCont[char.uuid] = cont
                if (value != null)
                    char.value = value
                if (!gatt.writeCharacteristic(char))
                    throw IOException("fail to write characteristic ${char.uuid}")
            }
        } finally {
            charWriteCont.remove(char.uuid)
        }
    }

    /**
     * Read notification or indication value. [enableNotification] or [enableIndication] must have
     * been called. Queued notification/indication will be returned immediately, or wait for the
     * next notification/indication come.
     */
    suspend fun readCharacteristicChange(char: BluetoothGattCharacteristic): ByteArray {
        val queuedData = charChangeQueue[char.uuid]?.takeIf { !it.isEmpty }?.popFirst()
        if (queuedData != null) return queuedData

        if (charChangeCont.containsKey(char.uuid))
            throw IllegalStateException("last read not finish")
        return try {
            suspendCancellableCoroutine { cont ->
                charChangeCont[char.uuid] = cont
            }
        } finally {
            charChangeCont.remove(char.uuid)
        }
    }

    /**
     * Remove queued notification & indication for given characteristic.
     * The next [readCharacteristicChange] call after it will return fresh value.
     *
     * @param char characteristic to remove. null for all characteristic.
     */
    fun clearGattCharacteristicChangeQueue(char: BluetoothGattCharacteristic? = null) {
        if (char == null)
            charChangeQueue.clear()
        else
            charChangeQueue.remove(char.uuid)
    }

}

/**
 * Connect to GATT and return GattIo. GATT services was discovered before it returned.
 */
suspend fun BluetoothDevice.connectGattIo(context: Context): GattIo {
    val io = GattIo(context, this)
    io.connect()
    return io
}