package com.omnicast.tv

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Demonio DLNA / UPnP MediaRenderer (Igual que AirScreen).
 * Permite enviar video/audio directo desde VLC, Windows "Transmitir en dispositivo", WebVideoCaster, BubbleUPnP, iOS/Android.
 */
class DlnaSsdpService(
    private val localIp: String,
    private val httpPort: Int,
    private val deviceName: String = "OmniCast Google TV"
) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var isRunning = false
    private var multicastSocket: MulticastSocket? = null
    val deviceUuid: String = UUID.nameUUIDFromBytes("OmniCastTV-$localIp".toByteArray()).toString()

    fun start() {
        if (isRunning) return
        isRunning = true

        executor.execute {
            try {
                val group = InetAddress.getByName("239.255.255.250")
                multicastSocket = MulticastSocket(1900).apply {
                    reuseAddress = true
                    joinGroup(group)
                }

                // Anunciar presencia periódicamente
                executor.execute {
                    while (isRunning) {
                        sendNotifyAlive(group)
                        try { Thread.sleep(15000) } catch (_: Exception) {}
                    }
                }

                val buf = ByteArray(4096)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    multicastSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.startsWith("M-SEARCH") && (
                        msg.contains("ssdp:all") ||
                        msg.contains("upnp:rootdevice") ||
                        msg.contains("MediaRenderer") ||
                        msg.contains("AVTransport")
                    )) {
                        sendSearchResponse(packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                Log.e("DlnaSsdp", "Error SSDP: ${e.message}")
            }
        }
    }

    private fun sendNotifyAlive(group: InetAddress) {
        val targets = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1"
        )
        for (nt in targets) {
            val usn = if (nt.startsWith("uuid:")) nt else "uuid:$deviceUuid::$nt"
            val msg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$localIp:$httpPort/dlna/device.xml\r\n" +
                    "NT: $nt\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/14 UPnP/1.1 OmniCast/2.0\r\n" +
                    "USN: $usn\r\n\r\n"
            val data = msg.toByteArray()
            try {
                multicastSocket?.send(DatagramPacket(data, data.size, group, 1900))
            } catch (_: Exception) {}
        }
    }

    private fun sendSearchResponse(addr: InetAddress, port: Int) {
        val targets = listOf(
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
        for (st in targets) {
            val resp = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://$localIp:$httpPort/dlna/device.xml\r\n" +
                    "SERVER: Android/14 UPnP/1.1 OmniCast/2.0\r\n" +
                    "ST: $st\r\n" +
                    "USN: uuid:$deviceUuid::$st\r\n\r\n"
            val data = resp.toByteArray()
            try {
                multicastSocket?.send(DatagramPacket(data, data.size, addr, port))
            } catch (_: Exception) {}
        }
    }

    fun getDeviceDescriptorXml(): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>OmniCast Native</manufacturer>
    <modelName>OmniCast Pro Renderer</modelName>
    <UDN>uuid:$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/dlna/AVTransport.xml</SCPDURL>
        <controlURL>/dlna/control/AVTransport</controlURL>
        <eventSubURL>/dlna/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/dlna/RenderingControl.xml</SCPDURL>
        <controlURL>/dlna/control/RenderingControl</controlURL>
        <eventSubURL>/dlna/event/RenderingControl</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

    fun stop() {
        isRunning = false
        try { multicastSocket?.close() } catch (_: Exception) {}
    }
}
