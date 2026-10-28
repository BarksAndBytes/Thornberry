package app.thornberry.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * A small slippy map written for this app (no map SDK, no Google libraries).
 * Background tiles come from tile.openstreetmap.org, fetched ONLY through our own Tor client (never directly),
 * kept in memory only (nothing written to disk). The tile server therefore learns which map area is being
 * viewed, from a Tor exit address, and nothing about who is looking or where anyone is.
 */
class MapView(ctx: Context) : View(ctx) {

    class Marker(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val accuracyM: Int,
        val ageMin: Long,
        /** Older to newer, as [lat, lon]. */
        val trail: List<DoubleArray>,
        val isMe: Boolean,
    )

    var onMarkerTap: ((Marker) -> Unit)? = null
    /** Long press on the map: the latitude and longitude under the finger (used to save a place). */
    var onPlacePick: ((Double, Double) -> Unit)? = null

    /** Tap (press and release) on a place's pin: open its properties. */
    var onPlaceEdit: ((String) -> Unit)? = null
    /** A place was dragged to a new spot (released). */
    var onPlaceMoved: ((String, Double, Double) -> Unit)? = null

    class PlaceShape(val id: String, val name: String, val lat: Double, val lon: Double, val radiusM: Int)
    private var shapes: List<PlaceShape> = emptyList()
    // while dragging, and until the saved position comes back in setPlaces, draw the dragged place here
    private var overrideId: String? = null
    private var overrideLat = 0.0
    private var overrideLon = 0.0
    // live size preview while the properties slider moves
    private var previewId: String? = null
    private var previewR = 0

    fun setPlaces(list: List<PlaceShape>) { shapes = list; if (dragId == null) overrideId = null; invalidate() }
    fun setPreview(id: String?, radiusM: Int) { previewId = id; previewR = radiusM; invalidate() }

    private var dragId: String? = null
    private var dragMoved = false
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var pinLongPressed = false

    private fun placePos(sh: PlaceShape) = if (sh.id == overrideId) overrideLat to overrideLon else sh.lat to sh.lon
    private fun placeRadius(sh: PlaceShape) = if (sh.id == previewId) previewR else sh.radiusM

    /** The place whose pin is under (x, y), if any (the nearest one). */
    private fun hitPlace(x: Float, y: Float): PlaceShape? {
        val cx = worldX(centerLon, zoom); val cy = worldY(centerLat, zoom)
        var best: PlaceShape? = null; var bestD = Float.MAX_VALUE
        for (sh in shapes) {
            val (la, lo) = placePos(sh)
            val dx = sx(lo, cx) - x; val dy = sy(la, cy) - y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            val mpp = 156543.03392 * cos(Math.toRadians(la)) / 2.0.pow(zoom)
            val rPx = (placeRadius(sh) / mpp).toFloat()
            val reach = max(32 * d, min(0.35f * rPx, 70 * d))
            if (dist <= reach && dist < bestD) { best = sh; bestD = dist }
        }
        return best
    }

    private var markers: List<Marker> = emptyList()
    private var centerLat = 20.0
    private var centerLon = 0.0
    private var zoom = 2.0
    private var needFit = true
    private var userMoved = false

    private val d = resources.displayMetrics.density
    private val tiles = object : LruCache<String, Bitmap>(160) {}
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val failedAt = Collections.synchronizedMap(HashMap<String, Long>())
    private val pool = Executors.newFixedThreadPool(3)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val palette = intArrayOf(
        Color.rgb(0xD8, 0x43, 0x15), Color.rgb(0x2E, 0x7D, 0x32), Color.rgb(0x8E, 0x24, 0xAA),
        Color.rgb(0x00, 0x83, 0x8F), Color.rgb(0xC2, 0x18, 0x5B), Color.rgb(0xF9, 0xA8, 0x25),
    )
    private val meColor = Color.rgb(0x15, 0x65, 0xC0)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            userMoved = true
            zoom = (zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            invalidate(); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress || dragId != null) return true
            userMoved = true
            val cx = worldX(centerLon, zoom) + dx
            val cy = worldY(centerLat, zoom) + dy
            centerLon = lonOf(cx, zoom); centerLat = latOf(cy, zoom)
            invalidate(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomBy(1.0); return true }
        override fun onLongPress(e: MotionEvent) {
            // Holding a pin does nothing, so the finger can start dragging it at any time.
            if (dragId != null || hitPlace(e.x, e.y) != null) return
            val x = worldX(centerLon, zoom) + (e.x - width / 2.0)
            val y = worldY(centerLat, zoom) + (e.y - height / 2.0)
            onPlacePick?.invoke(latOf(y, zoom), lonOf(x, zoom))
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!dragMoved) hitPlace(e.x, e.y)?.let { onPlaceEdit?.invoke(it.id); return true } // tap a pin: edit it
            val hit = markers.minByOrNull { dist2(it, e.x, e.y) } ?: return true
            if (dist2(hit, e.x, e.y) < (36 * d) * (36 * d)) onMarkerTap?.invoke(hit)
            return true
        }
    })

    // ------------------------------------------------------------ public

    fun setMarkers(list: List<Marker>) {
        markers = list
        applyFocus() // sets needFit=false when it succeeds
        if (needFit && width > 0 && list.isNotEmpty()) fit()
        invalidate()
    }

    private var pendingFocus: String? = null

    /** Centre on one person (now, or as soon as their position is known). */
    fun focus(id: String?) { pendingFocus = id; applyFocus() }

    private fun applyFocus() {
        val id = pendingFocus ?: return
        val m = markers.firstOrNull { it.id == id } ?: return
        pendingFocus = null; needFit = false; userMoved = true
        centerLat = m.lat; centerLon = m.lon; zoom = max(zoom, 15.0).coerceAtMost(MAX_ZOOM)
        invalidate()
    }

    fun zoomBy(delta: Double) { userMoved = true; zoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM); invalidate() }

    /** Show every marker. */
    fun fit() {
        userMoved = false
        if (width == 0 || height == 0) { needFit = true; return }
        needFit = false
        if (markers.isEmpty()) return
        val minLat = markers.minOf { it.lat }; val maxLat = markers.maxOf { it.lat }
        val minLon = markers.minOf { it.lon }; val maxLon = markers.maxOf { it.lon }
        centerLat = (minLat + maxLat) / 2; centerLon = (minLon + maxLon) / 2
        var z = 16.0
        while (z > MIN_ZOOM) {
            val w = abs(worldX(maxLon, z) - worldX(minLon, z))
            val h = abs(worldY(minLat, z) - worldY(maxLat, z))
            if (w < width * 0.7 && h < height * 0.7) break
            z -= 0.25
        }
        zoom = z
        invalidate()
    }

    private fun abs(v: Double) = if (v < 0) -v else v

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (needFit && markers.isNotEmpty()) fit() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pinLongPressed = false; dragMoved = false
                val hit = hitPlace(e.x, e.y)
                if (hit != null) {
                    val cx = worldX(centerLon, zoom); val cy = worldY(centerLat, zoom)
                    val (la, lo) = placePos(hit)
                    dragId = hit.id; dragDownX = e.x; dragDownY = e.y
                    grabDx = sx(lo, cx) - e.x; grabDy = sy(la, cy) - e.y
                } else dragId = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> { dragId = null; dragMoved = false } // a second finger: it is a zoom, not a drag
            MotionEvent.ACTION_MOVE -> {
                val id = dragId
                if (id != null && !pinLongPressed && e.pointerCount == 1) {
                    val dx = e.x - dragDownX; val dy = e.y - dragDownY
                    if (!dragMoved && dx * dx + dy * dy > (10 * d) * (10 * d)) dragMoved = true
                    if (dragMoved) {
                        val wx = worldX(centerLon, zoom) + (e.x + grabDx - width / 2.0)
                        val wy = worldY(centerLat, zoom) + (e.y + grabDy - height / 2.0)
                        overrideId = id; overrideLat = latOf(wy, zoom).coerceIn(-85.0, 85.0); overrideLon = lonOf(wx, zoom)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = dragId
                if (id != null && dragMoved && !pinLongPressed) onPlaceMoved?.invoke(id, overrideLat, overrideLon)
                dragId = null; dragMoved = false
            }
            MotionEvent.ACTION_CANCEL -> { dragId = null; dragMoved = false; if (overrideId != null) { overrideId = null; invalidate() } }
        }
        if (dragId == null) scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    // ------------------------------------------------------------ projection (Web Mercator)

    private fun size(z: Double) = 256.0 * 2.0.pow(z)
    private fun worldX(lon: Double, z: Double) = (lon + 180.0) / 360.0 * size(z)
    private fun worldY(lat: Double, z: Double): Double {
        val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * size(z)
    }
    private fun lonOf(x: Double, z: Double) = x / size(z) * 360.0 - 180.0
    private fun latOf(y: Double, z: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / size(z)))))

    private fun sx(lon: Double, cx: Double) = (worldX(lon, zoom) - cx + width / 2.0).toFloat()
    private fun sy(lat: Double, cy: Double) = (worldY(lat, zoom) - cy + height / 2.0).toFloat()

    private fun dist2(m: Marker, x: Float, y: Float): Float {
        val cx = worldX(centerLon, zoom); val cy = worldY(centerLat, zoom)
        val dx = sx(m.lon, cx) - x; val dy = sy(m.lat, cy) - y
        return dx * dx + dy * dy
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(0xE8, 0xE6, 0xE0))
        val cx = worldX(centerLon, zoom); val cy = worldY(centerLat, zoom)
        drawTiles(c, cx, cy)
        drawPlaces(c, cx, cy)
        drawMarkers(c, cx, cy)
        drawNotes(c)
    }

    private fun drawTiles(c: Canvas, cx: Double, cy: Double) {
        val tz = floor(zoom).toInt().coerceIn(0, 17)
        val ts = 256.0 * 2.0.pow(zoom - tz)
        val n = 1 shl tz
        val left = cx - width / 2.0; val top = cy - height / 2.0
        val x0 = floor(left / ts).toInt(); val x1 = floor((left + width) / ts).toInt()
        val y0 = floor(top / ts).toInt().coerceAtLeast(0); val y1 = floor((top + height) / ts).toInt().coerceAtMost(n - 1)
        val torUp = Node.torState == "CONNECTED" && Node.socksPort > 0
        paint.style = Paint.Style.FILL
        for (ty in y0..y1) for (tx in x0..x1) {
            val wrapped = ((tx % n) + n) % n
            val key = "$tz/$wrapped/$ty"
            val r = RectF(
                (tx * ts - left).toFloat(), (ty * ts - top).toFloat(),
                ((tx + 1) * ts - left).toFloat() + 1f, ((ty + 1) * ts - top).toFloat() + 1f,
            )
            val bmp = tiles.get(key)
            if (bmp != null) c.drawBitmap(bmp, null, r, null)
            else request(tz, wrapped, ty, key, torUp)
        }
    }

    /** Memory cache first (in [onDraw]), then the encrypted disk cache, then Tor. Works without Tor for saved areas. */
    private fun request(z: Int, x: Int, y: Int, key: String, torUp: Boolean) {
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < 30_000) return
        if (!inFlight.add(key)) return
        pool.execute {
            try {
                val useDisk = Node.mapCacheEnabled()
                val cached = if (useDisk) TileCache.get(z, x, y) else null
                val fresh = cached != null && System.currentTimeMillis() - cached.second < FRESH_MS
                var bytes: ByteArray? = if (fresh) cached!!.first else null
                if (bytes == null) {
                    if (torUp) {
                        try {
                            bytes = TileFetcher.fetch(Node.socksPort, z, x, y)
                            if (useDisk) TileCache.put(z, x, y, bytes)
                        } catch (e: Exception) {
                            if (cached == null) throw e
                            bytes = cached.first // stale copy is better than a blank square
                        }
                    } else {
                        bytes = cached?.first ?: throw IllegalStateException("not saved, tor not ready")
                    }
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("bad image")
                tiles.put(key, bmp)
                failedAt.remove(key)
            } catch (e: Exception) {
                failedAt[key] = System.currentTimeMillis()
                if (torUp) ChatLog.log("TILE_FAIL", e.javaClass.simpleName)
            } finally {
                inFlight.remove(key)
                postInvalidate()
            }
        }
    }

    private fun drawPlaces(c: Canvas, cx: Double, cy: Double) {
        val green = Color.rgb(0x2E, 0x7D, 0x5B)
        for (sh in shapes) {
            val (la, lo) = placePos(sh)
            val mpp = 156543.03392 * cos(Math.toRadians(la)) / 2.0.pow(zoom)
            val r = (placeRadius(sh) / mpp).toFloat()
            val x = sx(lo, cx); val y = sy(la, cy)
            val active = sh.id == dragId && dragMoved
            paint.style = Paint.Style.FILL; paint.color = green; paint.alpha = if (active) 70 else 45
            c.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.alpha = 200
            c.drawCircle(x, y, r, paint)
            paint.alpha = 255
            // the pin: tap to edit, press and drag to move
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            c.drawCircle(x, y, 10 * d, paint)
            paint.color = green
            c.drawCircle(x, y, 7 * d, paint)
            textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 12 * d
            textPaint.color = Color.WHITE; textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = 3 * d
            c.drawText(sh.name, x, y + 26 * d, textPaint)
            textPaint.style = Paint.Style.FILL; textPaint.color = green
            c.drawText(sh.name, x, y + 26 * d, textPaint)
        }
    }

    private fun drawMarkers(c: Canvas, cx: Double, cy: Double) {
        val mpp = 156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom) // metres per pixel (256 px tiles)
        for (m in markers) {
            val stale = m.ageMin > 15
            val color = when { stale -> Color.GRAY; m.isMe -> meColor; else -> palette[(m.id.hashCode() and 0x7fffffff) % palette.size] }
            if (m.trail.size > 1) {
                val p = Path()
                m.trail.forEachIndexed { i, pt ->
                    val x = sx(pt[1], cx); val y = sy(pt[0], cy)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = color; paint.alpha = 110
                paint.strokeJoin = Paint.Join.ROUND
                c.drawPath(p, paint)
                paint.alpha = 255
            }
            val x = sx(m.lon, cx); val y = sy(m.lat, cy)
            val accPx = (m.accuracyM / mpp).toFloat().coerceIn(0f, 300 * d)
            if (accPx > 14 * d) {
                paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = 40
                c.drawCircle(x, y, accPx, paint)
                paint.alpha = 255
            }
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            c.drawCircle(x, y, 14 * d, paint)
            paint.color = color
            c.drawCircle(x, y, 11 * d, paint)
            textPaint.color = Color.WHITE; textPaint.textSize = 13 * d; textPaint.textAlign = Paint.Align.CENTER
            c.drawText((m.name.firstOrNull() ?: '?').uppercase(), x, y + 4.5f * d, textPaint)
            val age = if (m.ageMin < 1) "now" else if (m.ageMin < 60) "${m.ageMin}m" else "${m.ageMin / 60}h"
            val label = "${m.name} · $age"
            textPaint.textSize = 11 * d
            textPaint.color = Color.WHITE; textPaint.style = Paint.Style.STROKE; textPaint.strokeWidth = 3 * d
            c.drawText(label, x, y + 28 * d, textPaint)
            textPaint.style = Paint.Style.FILL; textPaint.color = Color.rgb(0x20, 0x20, 0x20)
            c.drawText(label, x, y + 28 * d, textPaint)
        }
    }

    private fun drawNotes(c: Canvas) {
        textPaint.style = Paint.Style.FILL; textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = 10 * d
        textPaint.color = Color.rgb(0x30, 0x30, 0x30)
        c.drawText("© OpenStreetMap contributors", 6 * d, height - 6 * d, textPaint)
        if (Node.torState != "CONNECTED") {
            textPaint.textSize = 12 * d
            c.drawText("Waiting for Tor (${Node.torState.lowercase()}). Saved areas still show.", 8 * d, 18 * d, textPaint)
        } else if (inFlight.isNotEmpty()) {
            textPaint.textSize = 11 * d
            c.drawText("Loading map…", 8 * d, 18 * d, textPaint)
        }
    }

    companion object {
        /** Saved tiles younger than this are used without asking the server (OSM asks clients to cache for a week or more). */
        const val FRESH_MS = 14L * 24 * 3600 * 1000
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 17.99
    }
}

/** Fetches one OpenStreetMap tile through the local Tor SOCKS port. The host name is never resolved outside Tor. */
object TileFetcher {
    private const val HOST = "tile.openstreetmap.org"
    private const val MAX_BYTES = 400_000
    private const val USER_AGENT = "Thornberry-test/0.3 (private family location test app; contact via project owner)"

    fun fetch(socksPort: Int, z: Int, x: Int, y: Int): ByteArray {
        require(socksPort > 0) { "tor not ready" }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        Socket(proxy).use { raw ->
            raw.soTimeout = 60_000
            raw.connect(InetSocketAddress.createUnresolved(HOST, 443), 60_000)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            val out = ssl.outputStream
            out.write(
                ("GET /$z/$x/$y.png HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: $USER_AGENT\r\nAccept: image/png\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            val inp = ssl.inputStream
            val head = readHeaders(inp)
            val lines = head.split("\r\n")
            val status = lines[0].split(" ").getOrNull(1)?.toIntOrNull() ?: throw IllegalStateException("bad response")
            if (status != 200) throw IllegalStateException("http $status")
            val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
            val body = if (headers["transfer-encoding"]?.contains("chunked") == true) readChunked(inp) else readAll(inp, headers["content-length"]?.toIntOrNull())
            if (body.isEmpty()) throw IllegalStateException("empty")
            return body
        }
    }

    private fun readHeaders(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val v = inp.read()
            if (v < 0) throw IllegalStateException("eof in headers")
            b.write(v)
            last4 = (last4 shl 8) or v
            if (last4 == 0x0D0A0D0A) break
            if (b.size() > 16_384) throw IllegalStateException("headers too long")
        }
        return b.toString(Charsets.ISO_8859_1.name()).trimEnd()
    }

    private fun readAll(inp: InputStream, length: Int?): ByteArray {
        if (length != null && (length < 0 || length > MAX_BYTES)) throw IllegalStateException("bad length")
        val b = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            b.write(buf, 0, n)
            if (b.size() > MAX_BYTES) throw IllegalStateException("too large")
            if (length != null && b.size() >= length) break
        }
        return b.toByteArray()
    }

    private fun readLine(inp: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val v = inp.read()
            if (v < 0) throw IllegalStateException("eof")
            if (v == '\n'.code) break
            if (v != '\r'.code) b.append(v.toChar())
            if (b.length > 64) throw IllegalStateException("bad chunk line")
        }
        return b.toString()
    }

    private fun readChunked(inp: InputStream): ByteArray {
        val b = ByteArrayOutputStream()
        while (true) {
            val size = readLine(inp).substringBefore(';').trim().toIntOrNull(16) ?: throw IllegalStateException("bad chunk")
            if (size == 0) break
            if (size < 0 || b.size() + size > MAX_BYTES) throw IllegalStateException("too large")
            val buf = ByteArray(size)
            var got = 0
            while (got < size) {
                val n = inp.read(buf, got, size - got)
                if (n < 0) throw IllegalStateException("eof in chunk")
                got += n
            }
            b.write(buf)
            readLine(inp)
        }
        return b.toByteArray()
    }
}
