/**
 * @author wupanjie on 2017/12/4.
 */
package com.indoors

import jkid.JsonName

data class WiFiInfo(
    val BSSID: String,
    val RSSI: Int,
    val SSID: String)

data class WiFiFingerprint(
    @JsonName("wifi_stat") val fingerprint: List<WiFiInfo>,
    @JsonName("upload_time") val uploadTime : Long
)

data class NeedComputePosition(
    val fingerprint: List<WiFiInfo>
)

data class RoomPosition(val x: Double, val y: Double, val wifi_stats: List<WiFiFingerprint>) {

  val macRSSIMap : HashMap<String, Int>
    get() {
      val map = hashMapOf<String, ArrayList<Int>>()
      wifi_stats.forEach { fingerprint ->

        fingerprint.fingerprint.forEach {wifiInfo ->
          val list = map[wifiInfo.BSSID]
          if (list == null){
            map[wifiInfo.BSSID] = arrayListOf(wifiInfo.RSSI)
          }else{
            list += wifiInfo.RSSI
          }
        }

      }

      val result = hashMapOf<String, Int>()
      map.forEach { mac, rssiList ->
        result[mac] = rssiList.sum() / rssiList.size
      }

      return result
    }
}

data class Position(val x: Double, val y: Double)

data class Room(
    @JsonName("_id") val id: String,
    @JsonName("room_name") val name: String,
    val width : Double,
    val height : Double,
    @JsonName("image_url") val imageUrl: String = "",
    val positions: List<RoomPosition>)