package os.deckbridge.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.BatteryManager
import android.os.Build
import android.hardware.usb.UsbManager
import os.deckbridge.logic.DeviceIdentity
import os.deckbridge.logic.ListedDevice

fun readIdentity(context: Context): DeviceIdentity {
    val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memory = android.app.ActivityManager.MemoryInfo()
    activity.getMemoryInfo(memory)
    val ramMb = (memory.totalMem / (1024L * 1024L)).toInt()
    val soc = if (Build.VERSION.SDK_INT >= 31) {
        val model = Build.SOC_MODEL?.takeIf { it.isNotBlank() }
        val maker = Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() }
        when {
            model == null -> "UNKNOWN"
            maker == null -> model
            else -> "$maker $model"
        }
    } else {
        "UNKNOWN"
    }
    return DeviceIdentity(
        manufacturer = Build.MANUFACTURER.ifBlank { "UNKNOWN" },
        model = Build.MODEL.ifBlank { "UNKNOWN" },
        device = Build.DEVICE.ifBlank { "UNKNOWN" },
        hardware = Build.HARDWARE.ifBlank { "UNKNOWN" },
        soc = soc,
        androidRelease = Build.VERSION.RELEASE.ifBlank { "UNKNOWN" },
        sdkInt = Build.VERSION.SDK_INT,
        ramMb = ramMb,
        cores = Runtime.getRuntime().availableProcessors(),
    )
}

fun readBattery(context: Context): Pair<Int?, String> {
    val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null to "UNKNOWN"
    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
    val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val temperature = if (temp == Int.MIN_VALUE) "UNKNOWN" else String.format(java.util.Locale.US, "%.1f", temp / 10.0)
    return percent to temperature
}

fun listAudioOutputs(context: Context): List<ListedDevice> {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
        val channels = device.channelCounts.joinToString(",")
        val rates = device.sampleRates.joinToString(",")
        ListedDevice(
            kind = "audio",
            name = device.productName?.toString().orEmpty().ifBlank { "sem nome" },
            detail = "id=${device.id} tipo=${audioType(device.type)} canais=[$channels] taxas=[$rates]",
        )
    }
}

fun usbAudioDeviceId(context: Context): Int? {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val usb = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    return usb?.id
}

fun listUsb(context: Context): List<ListedDevice> {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    return manager.deviceList.values.map { device ->
        val classes = buildList {
            for (index in 0 until device.interfaceCount) {
                val item = device.getInterface(index)
                add(item.interfaceClass)
            }
        }
        val product = try {
            device.productName
        } catch (_: SecurityException) {
            null
        }
        val maker = try {
            device.manufacturerName
        } catch (_: SecurityException) {
            null
        }
        ListedDevice(
            kind = "usb",
            name = product ?: device.deviceName,
            detail = "fabricante=${maker ?: "UNKNOWN"} classes=$classes",
        )
    }
}

fun listMidiPorts(context: Context): List<ListedDevice> {
    val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        ?: return listOf(ListedDevice("midi", "MidiManager ausente", "UNKNOWN"))
    return manager.devices.map { device ->
        val outputs = device.ports.count { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
        val inputs = device.ports.count { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
        ListedDevice(
            kind = "midi",
            name = device.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: device.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "sem nome",
            detail = "saidas_para_o_app=$outputs entradas_do_app=$inputs",
        )
    }
}

private fun audioType(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "alto-falante"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "fone com fio"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headset com fio"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    else -> type.toString()
}
