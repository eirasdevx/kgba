package com.kgba.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Renderizador por líneas: modos 0-5, sprites normales y afines, ventanas, mosaico y blending. */
class Ppu(private val gba: Gba) {
    companion object {
        const val WIDTH = 240
        const val HEIGHT = 160
        private const val TRANSPARENT = -1
        private val OBJ_W = arrayOf(intArrayOf(8, 16, 32, 64), intArrayOf(16, 32, 32, 64), intArrayOf(8, 8, 16, 32))
        private val OBJ_H = arrayOf(intArrayOf(8, 16, 32, 64), intArrayOf(8, 8, 16, 32), intArrayOf(16, 32, 32, 64))

        /** Tabla BGR555 -> ARGB8888. */
        val RGB = IntArray(32768) { c ->
            val r = c and 31; val g = (c ushr 5) and 31; val b = (c ushr 10) and 31
            (0xFF shl 24) or (((r shl 3) or (r ushr 2)) shl 16) or (((g shl 3) or (g ushr 2)) shl 8) or ((b shl 3) or (b ushr 2))
        }
    }

    /** Imagen ARGB de 240x160 del último frame. */
    val frame = IntArray(WIDTH * HEIGHT)

    private val bgLine = Array(4) { IntArray(WIDTH) }
    private val objColor = IntArray(WIDTH)
    private val objPrio = IntArray(WIDTH)
    private val objSemi = BooleanArray(WIDTH)
    private val objWin = BooleanArray(WIDTH)
    private val order = IntArray(4)
    private val prios = IntArray(4)
    private val refX = IntArray(2)
    private val refY = IntArray(2)

    private val vram get() = gba.bus.vram
    private val pram get() = gba.bus.pram
    private val oam get() = gba.bus.oam

    private fun io16(off: Int) = gba.io.u16(off)
    private fun s16(off: Int) = gba.io.u16(off).toShort().toInt()
    private fun pal(i: Int) = pram.u16(i shl 1) and 0x7FFF

    fun bitmapMode(): Boolean = (io16(0) and 7) >= 3

    /** Una escritura en BGxX/BGxY recarga el punto de referencia interno. */
    fun latchRef(off: Int) {
        val i = if (off < 0x30) 0 else 1
        val base = if (i == 0) 0x28 else 0x38
        refX[i] = (gba.io.u32(base) shl 4) shr 4
        refY[i] = (gba.io.u32(base + 4) shl 4) shr 4
    }

    fun vblankReload() {
        latchRef(0x28); latchRef(0x38)
    }

    private fun advanceAffine() {
        refX[0] += s16(0x22); refY[0] += s16(0x26)
        refX[1] += s16(0x32); refY[1] += s16(0x36)
    }

    fun renderLine(y: Int) {
        val disp = io16(0)
        val lineBase = y * WIDTH
        if ((disp and 0x80) != 0) {
            java.util.Arrays.fill(frame, lineBase, lineBase + WIDTH, -1)
            advanceAffine()
            return
        }
        val mode = disp and 7
        val mosaic = io16(0x4C)
        var count = 0
        for (bg in 0..3) {
            if ((disp and (0x100 shl bg)) == 0) continue
            val ok = when (mode) {
                0 -> true
                1 -> bg <= 2
                2 -> bg >= 2
                3, 4, 5 -> bg == 2
                else -> false
            }
            if (!ok) continue
            val out = bgLine[bg]
            when {
                mode == 0 || (mode == 1 && bg < 2) -> renderText(bg, y, out, mosaic)
                mode <= 2 -> renderAffine(bg, out)
                else -> renderBitmap(mode, disp, out)
            }
            // inserción ordenada por prioridad (y por índice de fondo)
            val prio = io16(0x08 + bg * 2) and 3
            var k = count
            while (k > 0 && (io16(0x08 + order[k - 1] * 2) and 3) > prio) { order[k] = order[k - 1]; k-- }
            order[k] = bg
            count++
        }
        java.util.Arrays.fill(objColor, TRANSPARENT)
        java.util.Arrays.fill(objWin, false)
        if ((disp and 0x1000) != 0) renderSprites(y, disp, mode >= 3)
        compose(y, disp, count)
        advanceAffine()
    }

    // ---------------------------------------------------------------- fondos

    private fun renderText(bg: Int, y: Int, out: IntArray, mosaic: Int) {
        val cnt = io16(0x08 + bg * 2)
        val hofs = io16(0x10 + bg * 4) and 0x1FF
        val vofs = io16(0x12 + bg * 4) and 0x1FF
        val charBase = ((cnt ushr 2) and 3) * 0x4000
        val screenBase = ((cnt ushr 8) and 0x1F) * 0x800
        val color8 = (cnt and 0x80) != 0
        val size = cnt ushr 14
        val wMask = if ((size and 1) != 0) 511 else 255
        val hMask = if ((size and 2) != 0) 511 else 255
        val mos = (cnt and 0x40) != 0
        val mh = if (mos) (mosaic and 0xF) + 1 else 1
        val mv = if (mos) ((mosaic ushr 4) and 0xF) + 1 else 1
        val yy = if (mv > 1) y - y % mv else y
        val sy = (yy + vofs) and hMask
        val vram = vram
        val rowBase = ((sy and 255) ushr 3) * 32
        val blockY = when (size) { 2 -> sy ushr 8; 3 -> (sy ushr 8) * 2; else -> 0 }
        val pram = pram
        // La entrada del mapa solo cambia cada 8 píxeles: se lee una vez por tile
        var lastCol = -1
        var hflip = false
        var rowAddr = 0
        var palBase = 0
        var x = 0
        while (x < WIDTH) {
            val xx = if (mh > 1) x - x % mh else x
            val sx = (xx + hofs) and wMask
            val col = sx ushr 3
            if (col != lastCol) {
                lastCol = col
                val block = blockY + (if ((size and 1) != 0) sx ushr 8 else 0)
                val entry = vram.u16(screenBase + block * 0x800 + (rowBase + ((sx and 255) ushr 3)) * 2)
                hflip = (entry and 0x400) != 0
                val py = if ((entry and 0x800) != 0) 7 - (sy and 7) else sy and 7
                rowAddr = if (color8) charBase + (entry and 0x3FF) * 64 + py * 8
                else charBase + (entry and 0x3FF) * 32 + py * 4
                palBase = (entry ushr 12) shl 4
            }
            val px = if (hflip) 7 - (sx and 7) else sx and 7
            out[x] = if (color8) {
                val addr = rowAddr + px
                if (addr >= 0x10000) TRANSPARENT else {
                    val ci = vram.u8(addr); if (ci == 0) TRANSPARENT else pram.u16(ci shl 1) and 0x7FFF
                }
            } else {
                val addr = rowAddr + (px ushr 1)
                if (addr >= 0x10000) TRANSPARENT else {
                    val b = vram.u8(addr)
                    val ci = if ((px and 1) != 0) b ushr 4 else b and 0xF
                    if (ci == 0) TRANSPARENT else pram.u16((palBase + ci) shl 1) and 0x7FFF
                }
            }
            x++
        }
    }

    private fun renderAffine(bg: Int, out: IntArray) {
        val cnt = io16(0x08 + bg * 2)
        val size = 128 shl (cnt ushr 14)
        val tiles = size ushr 3
        val charBase = ((cnt ushr 2) and 3) * 0x4000
        val screenBase = ((cnt ushr 8) and 0x1F) * 0x800
        val wrap = (cnt and 0x2000) != 0
        val i = bg - 2
        val pa = s16(0x20 + i * 16)
        val pc = s16(0x24 + i * 16)
        var fx = refX[i]
        var fy = refY[i]
        val vram = vram
        for (x in 0 until WIDTH) {
            var tx = fx shr 8
            var ty = fy shr 8
            fx += pa; fy += pc
            if (wrap) { tx = tx and (size - 1); ty = ty and (size - 1) }
            else if (tx < 0 || ty < 0 || tx >= size || ty >= size) { out[x] = TRANSPARENT; continue }
            val tile = vram.u8(screenBase + (ty ushr 3) * tiles + (tx ushr 3))
            val ci = vram.u8(charBase + tile * 64 + (ty and 7) * 8 + (tx and 7))
            out[x] = if (ci == 0) TRANSPARENT else pal(ci)
        }
    }

    private fun renderBitmap(mode: Int, disp: Int, out: IntArray) {
        val pa = s16(0x20)
        val pc = s16(0x24)
        var fx = refX[0]
        var fy = refY[0]
        val page = if ((disp and 0x10) != 0) 0xA000 else 0
        val vram = vram
        for (x in 0 until WIDTH) {
            val tx = fx shr 8
            val ty = fy shr 8
            fx += pa; fy += pc
            out[x] = when (mode) {
                3 -> if (tx in 0 until 240 && ty in 0 until 160) vram.u16((ty * 240 + tx) * 2) and 0x7FFF else TRANSPARENT
                4 -> if (tx in 0 until 240 && ty in 0 until 160) {
                    val ci = vram.u8(page + ty * 240 + tx); if (ci == 0) TRANSPARENT else pal(ci)
                } else TRANSPARENT
                else -> if (tx in 0 until 160 && ty in 0 until 128) vram.u16(page + (ty * 160 + tx) * 2) and 0x7FFF else TRANSPARENT
            }
        }
    }

    // ---------------------------------------------------------------- sprites

    private fun renderSprites(y: Int, disp: Int, bitmap: Boolean) {
        val oam = oam
        val vram = vram
        val oneD = (disp and 0x40) != 0
        val mosaic = io16(0x4C)
        for (i in 0 until 128) {
            val a0 = oam.u16(i * 8)
            val a1 = oam.u16(i * 8 + 2)
            val a2 = oam.u16(i * 8 + 4)
            val affine = (a0 and 0x100) != 0
            if (!affine && (a0 and 0x200) != 0) continue
            val objMode = (a0 ushr 10) and 3
            if (objMode == 3) continue
            val shape = a0 ushr 14
            if (shape == 3) continue
            val sz = a1 ushr 14
            val w = OBJ_W[shape][sz]
            val h = OBJ_H[shape][sz]
            val dbl = affine && (a0 and 0x200) != 0
            val bw = if (dbl) w * 2 else w
            val bh = if (dbl) h * 2 else h
            var rel = (y - (a0 and 0xFF)) and 0xFF
            if (rel >= bh) continue
            var sx = a1 and 0x1FF
            if (sx >= 240) sx -= 512
            val tile = a2 and 0x3FF
            if (bitmap && tile < 512) continue
            val prio = (a2 ushr 10) and 3
            val palBank = a2 ushr 12
            val color8 = (a0 and 0x2000) != 0
            val mos = (a0 and 0x1000) != 0
            if (mos) {
                val mv = ((mosaic ushr 12) and 0xF) + 1
                rel -= rel % mv
            }
            val rowTiles = if (oneD) (w ushr 3) * (if (color8) 2 else 1) else 32
            if (!affine) {
                var ty = rel
                if ((a1 and 0x2000) != 0) ty = h - 1 - ty
                val hflip = (a1 and 0x1000) != 0
                for (px in 0 until w) {
                    val x = sx + px
                    if (x < 0 || x >= WIDTH) continue
                    val tx = if (hflip) w - 1 - px else px
                    val c = objPixel(vram, tile, tx, ty, color8, palBank, rowTiles, oneD)
                    if (c != TRANSPARENT) plot(x, c, prio, objMode)
                }
            } else {
                val pBase = ((a1 ushr 9) and 0x1F) * 32
                val pa = oam.u16(pBase + 6).toShort().toInt()
                val pb = oam.u16(pBase + 14).toShort().toInt()
                val pc = oam.u16(pBase + 22).toShort().toInt()
                val pd = oam.u16(pBase + 30).toShort().toInt()
                val dy = rel - bh / 2
                val halfW = w / 2
                val halfH = h / 2
                for (px in 0 until bw) {
                    val x = sx + px
                    if (x < 0 || x >= WIDTH) continue
                    val dx = px - bw / 2
                    val tx = ((pa * dx + pb * dy) shr 8) + halfW
                    val ty = ((pc * dx + pd * dy) shr 8) + halfH
                    if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue
                    val c = objPixel(vram, tile, tx, ty, color8, palBank, rowTiles, oneD)
                    if (c != TRANSPARENT) plot(x, c, prio, objMode)
                }
            }
        }
    }

    private fun objPixel(vram: ByteArray, tile: Int, tx: Int, ty: Int, color8: Boolean, palBank: Int, rowTiles: Int, oneD: Boolean): Int {
        return if (color8) {
            val t = (if (oneD) tile else tile and 1.inv()) + (ty ushr 3) * rowTiles + (tx ushr 3) * 2
            val addr = 0x10000 + ((t * 32) and 0x7FFF) + (ty and 7) * 8 + (tx and 7)
            val ci = vram.u8(addr)
            if (ci == 0) TRANSPARENT else pal(256 + ci)
        } else {
            val t = tile + (ty ushr 3) * rowTiles + (tx ushr 3)
            val addr = 0x10000 + ((t * 32) and 0x7FFF) + (ty and 7) * 4 + ((tx and 7) ushr 1)
            val b = vram.u8(addr)
            val ci = if ((tx and 1) != 0) b ushr 4 else b and 0xF
            if (ci == 0) TRANSPARENT else pal(256 + palBank * 16 + ci)
        }
    }

    private fun plot(x: Int, color: Int, prio: Int, objMode: Int) {
        if (objMode == 2) { objWin[x] = true; return }
        if (objColor[x] == TRANSPARENT || prio < objPrio[x]) {
            objColor[x] = color
            objPrio[x] = prio
            objSemi[x] = objMode == 1
        }
    }

    // ---------------------------------------------------------------- composición

    private fun inRange(p: Int, lo: Int, hi: Int): Boolean = if (lo <= hi) p >= lo && p < hi else p >= lo || p < hi

    private fun compose(y: Int, disp: Int, nBg: Int) {
        val lineBase = y * WIDTH
        val backdrop = pal(0)
        val win0 = (disp and 0x2000) != 0
        val win1 = (disp and 0x4000) != 0
        val winObj = (disp and 0x8000) != 0 && (disp and 0x1000) != 0
        val anyWin = (disp and 0xE000) != 0
        val win0H = io16(0x40); val win1H = io16(0x42)
        val win0Y = win0 && inRange(y, io16(0x44) ushr 8, io16(0x44) and 0xFF)
        val win1Y = win1 && inRange(y, io16(0x46) ushr 8, io16(0x46) and 0xFF)
        val winIn = io16(0x48); val winOut = io16(0x4A)
        val bldcnt = io16(0x50)
        val effect = (bldcnt ushr 6) and 3
        val t1 = bldcnt and 0x3F
        val t2 = (bldcnt ushr 8) and 0x3F
        val alpha = io16(0x52)
        val eva = minOf(16, alpha and 0x1F)
        val evb = minOf(16, (alpha ushr 8) and 0x1F)
        val evy = minOf(16, io16(0x54) and 0x1F)
        val objOn = (disp and 0x1000) != 0
        val prios = prios
        for (k in 0 until nBg) prios[k] = io16(0x08 + order[k] * 2) and 3

        for (x in 0 until WIDTH) {
            val mask = if (!anyWin) 0x3F else when {
                win0Y && inRange(x, win0H ushr 8, win0H and 0xFF) -> winIn and 0x3F
                win1Y && inRange(x, win1H ushr 8, win1H and 0xFF) -> (winIn ushr 8) and 0x3F
                winObj && objWin[x] -> (winOut ushr 8) and 0x3F
                else -> winOut and 0x3F
            }
            var top = backdrop; var topL = 5
            var sec = backdrop; var secL = 5
            var found = 0
            var objPending = objOn && (mask and 0x10) != 0 && objColor[x] != TRANSPARENT
            var k = 0
            while (found < 2) {
                val bgPrio = if (k < nBg) prios[k] else 4
                var color: Int
                var layer: Int
                if (objPending && objPrio[x] <= bgPrio) {
                    color = objColor[x]; layer = 4; objPending = false
                } else {
                    if (k >= nBg) break
                    val bg = order[k++]
                    if ((mask and (1 shl bg)) == 0) continue
                    color = bgLine[bg][x]
                    if (color == TRANSPARENT) continue
                    layer = bg
                }
                if (found == 0) { top = color; topL = layer } else { sec = color; secL = layer }
                found++
            }
            var out = top
            val semi = topL == 4 && objSemi[x]
            if (semi && (t2 and (1 shl secL)) != 0) {
                out = blend(top, sec, eva, evb)
            } else if ((mask and 0x20) != 0 && (t1 and (1 shl topL)) != 0) {
                when (effect) {
                    1 -> if ((t2 and (1 shl secL)) != 0) out = blend(top, sec, eva, evb)
                    2 -> out = brighten(top, evy)
                    3 -> out = darken(top, evy)
                }
            }
            frame[lineBase + x] = RGB[out]
        }
    }

    private fun blend(a: Int, b: Int, eva: Int, evb: Int): Int {
        val r = minOf(31, ((a and 31) * eva + (b and 31) * evb) shr 4)
        val g = minOf(31, (((a ushr 5) and 31) * eva + ((b ushr 5) and 31) * evb) shr 4)
        val bl = minOf(31, (((a ushr 10) and 31) * eva + ((b ushr 10) and 31) * evb) shr 4)
        return r or (g shl 5) or (bl shl 10)
    }

    private fun brighten(a: Int, evy: Int): Int {
        var r = a and 31; var g = (a ushr 5) and 31; var b = (a ushr 10) and 31
        r += ((31 - r) * evy) shr 4; g += ((31 - g) * evy) shr 4; b += ((31 - b) * evy) shr 4
        return r or (g shl 5) or (b shl 10)
    }

    private fun darken(a: Int, evy: Int): Int {
        var r = a and 31; var g = (a ushr 5) and 31; var b = (a ushr 10) and 31
        r -= (r * evy) shr 4; g -= (g * evy) shr 4; b -= (b * evy) shr 4
        return r or (g shl 5) or (b shl 10)
    }

    internal fun save(o: DataOutputStream) { o.ints(refX); o.ints(refY) }
    internal fun load(i: DataInputStream) { i.ints(refX); i.ints(refY) }
}
