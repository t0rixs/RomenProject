package com.example.romenlogger.map

import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource

/**
 * 国土地理院タイル（地理院タイル）用の [ITileSource] 定義。
 *
 * 参考: https://maps.gsi.go.jp/development/ichiran.html
 *
 * 利用上の注意:
 *  - 出典（クレジット）として「地理院タイル」を画面上に明示する必要がある。
 *  - 商用利用や大量取得は利用規約を確認。
 */
object GsiTileSource {

    /** 標準地図（ラスター）。最大ズーム 18。 */
    val Standard: ITileSource = XYTileSource(
        "GSI-STD",
        5, 18, 256, ".png",
        arrayOf("https://cyberjapandata.gsi.go.jp/xyz/std/"),
        "地理院タイル"
    )

    /** 淡色地図。 */
    val Pale: ITileSource = XYTileSource(
        "GSI-PALE",
        5, 18, 256, ".png",
        arrayOf("https://cyberjapandata.gsi.go.jp/xyz/pale/"),
        "地理院タイル"
    )

    /** 航空写真シームレス。 */
    val SeamlessPhoto: ITileSource = XYTileSource(
        "GSI-PHOTO",
        2, 18, 256, ".jpg",
        arrayOf("https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/"),
        "地理院タイル（シームレス空中写真）"
    )
}
