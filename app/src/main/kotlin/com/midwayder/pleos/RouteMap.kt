package com.midwayder.pleos

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * 서버 응답(경로 폴리라인·출발·목적지·후보)을 지도에 그린다. 지도는 표시용이며 경로 계산은 서버가 한다.
 * 바탕 지도는 OpenFreeMap 벡터 타일에 Pleos 내비 톤(연회색 바탕·푸른 회색 도로) 스타일을 입힌 것이다(assets/map).
 * 다크(시스템 야간 모드)는 다크 스타일을 쓴다. 경로·핀 색은 values / values-night.
 */
class RouteMap(private val view: MapView) {
    private val density = view.resources.displayMetrics.density
    private val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun color(id: Int) = ContextCompat.getColor(view.context, id)
    private val routeColor = color(R.color.route)
    private val selectedColor = color(R.color.pin_selected)
    private val onSelectedColor = color(R.color.on_pin_selected)
    private val otherColor = color(R.color.pin_other)
    private val originColor = color(R.color.pin_origin)
    private val destinationColor = color(R.color.pin_destination)

    private var map: MapLibreMap? = null
    private var style: Style? = null
    /** 스타일이 아직 안 읽혔을 때 들어온 마지막 그리기 요청. 준비되면 한 번 그린다. */
    private var pending: (() -> Unit)? = null

    init {
        val json = view.context.assets.open(if (night) "map/pleos-dark.json" else "map/pleos-light.json").bufferedReader().use { it.readText() }
        view.getMapAsync { m ->
            m.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false // 출처는 레이아웃의 mapNote 로 표기한다
            }
            m.cameraPosition = CameraPosition.Builder().target(LatLng(37.5665, 126.9780)).zoom(10.5).build()
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                s.addSource(GeoJsonSource(ROUTE))
                s.addSource(GeoJsonSource(PINS))
                // 바탕색 테두리 위에 경로를 그려 도로보다 확실히 튀게 한다.
                s.addLayer(LineLayer("route-casing", ROUTE).withProperties(
                    lineColor(if (night) Color.parseColor("#202124") else Color.WHITE), lineWidth(9f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(LineLayer("route", ROUTE).withProperties(
                    lineColor(routeColor), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                s.addLayer(SymbolLayer("pins", PINS).withProperties(
                    iconImage(get("icon")), iconAnchor(get("anchor")), symbolSortKey(get("order")),
                    iconAllowOverlap(true), iconIgnorePlacement(true)))
                map = m
                style = s
                pending?.invoke()
                pending = null
            }
        }
    }

    fun showOrigin(origin: Place) = draw(listOf(origin), null, emptyList(), -1)

    fun showJourney(origin: Place, destination: Place) = draw(listOf(origin, destination), null, emptyList(), -1)

    fun showResult(origin: Place, destination: Place, result: SearchResult, selected: Int) =
        draw(listOf(origin, destination), result.polyline, result.stops, selected)

    private fun draw(ends: List<Place>, polyline: List<GeoPoint>?, stops: List<Stop>, selected: Int) {
        val m = map
        val s = style
        if (m == null || s == null) {
            pending = { draw(ends, polyline, stops, selected) }
            return
        }
        val points = mutableListOf<LatLng>()
        val line = polyline?.takeIf { it.size >= 2 }
        s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            if (line == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.lng, it.lat) }))))
        line?.let { l -> points += l.map { LatLng(it.lat, it.lng) } }

        // order 가 클수록 위에 그린다: 나머지 후보 → 출발·목적지 → 선택한 후보 순.
        val features = mutableListOf<Feature>()
        fun add(point: GeoPoint, icon: String, bitmap: () -> Bitmap, bottom: Boolean, order: Int) {
            if (s.getImage(icon) == null) s.addImage(icon, bitmap())
            features += Feature.fromGeometry(Point.fromLngLat(point.lng, point.lat)).apply {
                addStringProperty("icon", icon)
                addStringProperty("anchor", if (bottom) Property.ICON_ANCHOR_BOTTOM else Property.ICON_ANCHOR_CENTER)
                addNumberProperty("order", order)
            }
            points += LatLng(point.lat, point.lng)
        }
        stops.forEachIndexed { index, stop ->
            if (index != selected) add(stop.coordinate, "other-${index + 1}", { pin("${index + 1}", otherColor, Color.WHITE, 30) }, true, 1)
            else add(stop.coordinate, "selected-${index + 1}", { pin("${index + 1}", selectedColor, onSelectedColor, 42) }, true, 3)
        }
        ends.forEachIndexed { index, place ->
            if (index == 0) add(place.coordinate, "origin", { originDot() }, false, 2)
            else add(place.coordinate, "destination", { pin("", destinationColor, Color.WHITE, 36, flag = true) }, true, 2)
        }
        s.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(FeatureCollection.fromFeatures(features))

        // 지도 화면이 막 보이기 시작한 때는 크기가 0 이라 범위 계산이 틀어진다. 레이아웃이 끝난 뒤 맞춘다.
        view.post {
            when {
                points.size >= 2 -> m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), (64 * density).toInt()))
                points.size == 1 -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 14.5), 700)
            }
        }
    }

    /** 물방울 핀(흰 테두리). 순위 번호 또는 목적지 깃발을 넣는다. */
    private fun pin(text: String, fillColor: Int, labelColor: Int, sizeDp: Int, flag: Boolean = false): Bitmap {
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
        return bitmap
    }

    /** 출발(현재 위치): Pleos 내비처럼 파란 원 + 흰 테두리 + 흰 화살표. */
    private fun originDot(): Bitmap {
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
        return bitmap
    }

    private companion object {
        const val ROUTE = "route-source"
        const val PINS = "pin-source"
    }
}
