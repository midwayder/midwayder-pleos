package com.midwayder.pleos

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.GeoPoint as OsmPoint

/**
 * 서버 응답(경로 폴리라인·출발·목적지·후보)을 osmdroid 지도에 그린다. 지도는 표시용이며 경로 계산은 서버가 한다.
 * 밝은 화면은 타일 채도를 낮추고, 다크(시스템 야간 모드)는 타일을 반전·탈색한다. 경로·핀 색은 values / values-night.
 */
class RouteMap(private val map: MapView) {
    private val density = map.resources.displayMetrics.density
    private val night = (map.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun color(id: Int) = ContextCompat.getColor(map.context, id)
    private val routeColor = color(R.color.route)
    private val selectedColor = color(R.color.pin_selected)
    private val onSelectedColor = color(R.color.on_pin_selected)
    private val otherColor = color(R.color.pin_other)
    private val originColor = color(R.color.pin_origin)
    private val destinationColor = color(R.color.pin_destination)

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.overlayManager.tilesOverlay.setColorFilter(if (night) nightFilter() else dayFilter())
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
            // 흰(다크는 바탕색) 테두리 위에 경로를 그려 지도 글자·도로보다 확실히 튀게 한다.
            map.overlays.add(routeLine(line, if (night) Color.parseColor("#202124") else Color.WHITE, 9f))
            map.overlays.add(routeLine(line, routeColor, 6f))
            points += line.map { it.osm() }
        }
        // 뒤에 추가한 overlay 가 위에 그려진다: 나머지 후보 → 출발·목적지 → 선택한 후보 순.
        stops.forEachIndexed { index, stop ->
            if (index != selected) map.overlays.add(marker(stop.coordinate, pin("${index + 1}", otherColor, Color.WHITE, 30), bottom = true))
            points += stop.coordinate.osm()
        }
        ends.forEachIndexed { index, place ->
            map.overlays.add(if (index == 0) marker(place.coordinate, originDot(), bottom = false)
                else marker(place.coordinate, pin("", destinationColor, Color.WHITE, 36, flag = true), bottom = true))
            points += place.coordinate.osm()
        }
        stops.getOrNull(selected)?.let { map.overlays.add(marker(it.coordinate, pin("${selected + 1}", selectedColor, onSelectedColor, 42), bottom = true)) }
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

    private fun routeLine(line: List<GeoPoint>, color: Int, widthDp: Float) = Polyline(map).apply {
        setPoints(line.map { it.osm() })
        outlinePaint.color = color
        outlinePaint.strokeWidth = widthDp * density
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
    }

    /** bottom=true 면 핀 끝이 좌표를 가리키게, false 면 가운데를 맞춘다. */
    private fun marker(point: GeoPoint, drawable: Drawable, bottom: Boolean): Marker = Marker(map).apply {
        position = point.osm()
        setAnchor(Marker.ANCHOR_CENTER, if (bottom) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
        icon = drawable
        setInfoWindow(null)
    }

    /** 물방울 핀(흰 테두리). 순위 번호 또는 목적지 깃발을 넣는다. */
    private fun pin(text: String, fillColor: Int, labelColor: Int, sizeDp: Int, flag: Boolean = false): Drawable {
        val w = (sizeDp * density).toInt()
        val h = (w * 1.3f).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = 2.5f * density
        val r = w / 2f - stroke
        val cx = w / 2f
        val cy = r + stroke
        val shape = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h - stroke)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke * 2; strokeJoin = Paint.Join.ROUND })
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = labelColor }
        if (flag) {
            // design/out/pin_destination.svg 의 깃발: 깃대 + 삼각 깃발
            val u = r / 7f
            ink.strokeWidth = 1.6f * u; ink.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx - 2.5f * u, cy - 4f * u, cx - 2.5f * u, cy + 4.5f * u, ink)
            canvas.drawPath(Path().apply {
                moveTo(cx - 2.5f * u, cy - 4f * u); lineTo(cx + 3.5f * u, cy - 2f * u); lineTo(cx - 2.5f * u, cy); close()
            }, ink)
        } else if (text.isNotEmpty()) {
            ink.textAlign = Paint.Align.CENTER
            ink.textSize = r * (if (text.length > 1) 0.95f else 1.15f)
            ink.isFakeBoldText = true
            canvas.drawText(text, cx, cy - (ink.descent() + ink.ascent()) / 2, ink)
        }
        return BitmapDrawable(map.resources, bitmap)
    }

    /** 출발(현재 위치): Pleos 내비처럼 파란 원 + 흰 테두리 + 흰 화살표. */
    private fun originDot(): Drawable {
        val size = (30 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawCircle(c, c, c - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = originColor })
        val a = size * 0.22f
        canvas.drawPath(Path().apply {
            moveTo(c, c - a * 1.1f); lineTo(c + a * 0.8f, c + a); lineTo(c, c + a * 0.45f); lineTo(c - a * 0.8f, c + a); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return BitmapDrawable(map.resources, bitmap)
    }

    /** 밝은 화면: OSM 기본 타일의 채도를 낮춰 글자·아이콘이 경로보다 튀지 않게 한다. */
    private fun dayFilter() = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })

    /** 다크: 밝은 OSM 타일을 반전·탈색해 야간 차량 화면 톤으로 만든다. */
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
}
