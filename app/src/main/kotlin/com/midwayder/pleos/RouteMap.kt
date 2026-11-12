package com.midwayder.pleos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.view.doOnLayout
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmPoint

/** 서버 응답(경로 폴리라인·A/B·후보 C)을 osmdroid 지도에 그린다. 지도는 표시용이며 경로 계산은 서버가 한다. */
class RouteMap(private val map: MapView) {
    private val density = map.resources.displayMetrics.density

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.overlayManager.tilesOverlay.setColorFilter(nightFilter())
        map.controller.setZoom(11.5)
        map.controller.setCenter(OsmPoint(37.5665, 126.9780))
    }

    fun showOrigin(origin: Place) = draw(listOf(origin), null, emptyList(), -1)

    fun showJourney(origin: Place, destination: Place) = draw(listOf(origin, destination), null, emptyList(), -1)

    fun showResult(origin: Place, destination: Place, result: SearchResult, selected: Int) =
        draw(listOf(origin, destination), result.polyline, result.stops, selected)

    private fun draw(ends: List<Place>, polyline: List<GeoPoint>?, stops: List<Stop>, selected: Int) {
        map.overlays.clear()
        val points = mutableListOf<OsmPoint>()
        polyline?.takeIf { it.size >= 2 }?.let { line ->
            val overlay = Polyline(map).apply {
                setPoints(line.map { it.osm() })
                outlinePaint.color = ROUTE
                outlinePaint.strokeWidth = 5 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            map.overlays.add(overlay)
            points += line.map { it.osm() }
        }
        // 뒤에 추가한 overlay 가 위에 그려진다: 나머지 후보 → A/B → 선택한 후보 순.
        stops.forEachIndexed { index, stop ->
            if (index != selected) map.overlays.add(marker(stop.coordinate, "${index + 1}", STOP, 24))
            points += stop.coordinate.osm()
        }
        ends.forEachIndexed { index, place ->
            map.overlays.add(marker(place.coordinate, if (index == 0) "A" else "B", END, 30))
            points += place.coordinate.osm()
        }
        stops.getOrNull(selected)?.let { map.overlays.add(marker(it.coordinate, "${selected + 1}", SELECTED, 36)) }
        map.invalidate()
        // 지도 화면이 막 보이기 시작한 때는 크기가 0 이라 범위 계산이 틀어진다. 레이아웃이 끝난 뒤 맞춘다.
        map.doOnLayout {
            when {
                // 위 요약 카드·아래 버튼 바가 지도를 덮으므로 여백을 크게 둔다.
                points.size >= 2 -> map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.1f), false, (96 * density).toInt())
                points.size == 1 -> map.controller.animateTo(points[0], 14.5, 700L)
            }
        }
    }

    private fun marker(point: GeoPoint, label: String, color: Int, sizeDp: Int): Marker = Marker(map).apply {
        position = point.osm()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = badge(label, color, sizeDp)
        setInfoWindow(null)
    }

    private fun badge(text: String, color: Int, sizeDp: Int): Drawable {
        val size = (sizeDp * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
        val radius = size / 2f - ring.strokeWidth
        canvas.drawCircle(size / 2f, size / 2f, radius, fill)
        canvas.drawCircle(size / 2f, size / 2f, radius, ring)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * (if (text.length > 1) 0.42f else 0.52f)
            isFakeBoldText = true
        }
        val baseline = size / 2f - (label.descent() + label.ascent()) / 2
        canvas.drawText(text, size / 2f, baseline, label)
        return BitmapDrawable(map.resources, bitmap)
    }

    /** 밝은 OSM 타일을 반전·탈색해 야간 차량 화면 톤으로 만든다. */
    private fun nightFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val tone = ColorMatrix().apply { setSaturation(0.25f) }
        val dim = ColorMatrix(floatArrayOf(
            0.82f, 0f, 0f, 0f, 6f,
            0f, 0.84f, 0f, 0f, 8f,
            0f, 0f, 0.92f, 0f, 14f,
            0f, 0f, 0f, 1f, 0f,
        ))
        return ColorMatrixColorFilter(ColorMatrix(invert).apply { postConcat(tone); postConcat(dim) })
    }

    private fun GeoPoint.osm() = OsmPoint(lat, lng)

    private companion object {
        val ROUTE = Color.parseColor("#8FA8FF")
        val STOP = Color.parseColor("#5B6B8C")
        val SELECTED = Color.parseColor("#2E55E0")
        val END = Color.parseColor("#2FB76A")
    }
}
