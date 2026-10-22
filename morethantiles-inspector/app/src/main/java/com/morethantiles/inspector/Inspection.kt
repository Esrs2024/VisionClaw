package com.morethantiles.inspector

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkItem(val description: String, val unit: String, val quantity: Double?,
                    val price: Double?) {
    fun amount(): Double? = if (quantity != null && price != null) quantity * price else null
}

class Inspection(private val ctx: Context) {
    val dateCode = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
    private val dir = File(ctx.filesDir, "inspections/" + dateCode).apply { mkdirs() }
    var client = ""
    var address = ""
    var vatRate: Double? = null
    val works = mutableListOf<WorkItem>()
    val notes = mutableListOf<String>()
    var photos = 0
    private var photoIndex = 0
    var signatureAllowed = false

    fun addObservation(words: String, jpeg: ByteArray?) {
        notes.add(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + words)
        if (jpeg != null) savePhoto(jpeg)
        persist()
    }
    fun savePhoto(jpeg: ByteArray) {
        if (photos >= 90) return
        photoIndex++
        File(dir, "foto-" + photoIndex.toString().padStart(3, '0') + ".jpg").writeBytes(jpeg)
        photos++
        persist()
    }

    /** The agent sends the entire revised list of work items, not deltas. */
    fun receive(raw: String): String {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val json = try { JSONObject(cleaned) } catch (_: Exception) {
            notes.add("AGENTE: " + raw.take(500))
            persist()
            return raw
        }
        if (json.has("client")) client = json.optString("client").trim()
        if (json.has("address")) address = json.optString("address").trim()
        if (json.has("vatRate") && !json.isNull("vatRate")) {
            val proposed = json.optDouble("vatRate", Double.NaN)
            if (!proposed.isNaN() && proposed in 0.0..100.0) vatRate = proposed
        }
        if (json.has("works")) {
            val arr = json.optJSONArray("works") ?: JSONArray()
            works.clear()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val desc = item.optString("description").trim()
                if (desc.isBlank()) continue
                val q = if (!item.isNull("quantity") && item.has("quantity"))
                    item.optDouble("quantity").takeIf { !it.isNaN() && it > 0 } else null
                val p = if (!item.isNull("price") && item.has("price"))
                    item.optDouble("price").takeIf { !it.isNaN() && it >= 0 } else null
                works.add(WorkItem(desc, item.optString("unit", "stuk"), q, p))
            }
        }
        persist()
        return json.optString("say", json.optString("question", "Inspección actualizada."))
    }

    fun readyForOfficial(): Boolean = client.isNotBlank() && address.isNotBlank() &&
        vatRate != null && signatureAllowed && signatureFile().exists() &&
        works.isNotEmpty() && works.all { it.quantity != null && it.price != null }

    fun saveSignature(bytes: ByteArray) {
        // Only image bytes selected by the user, never a generated signature.
        File(ctx.filesDir, "mtt-real-signature").writeBytes(bytes)
        signatureAllowed = false
    }
    private fun signatureFile() = File(ctx.filesDir, "mtt-real-signature")

    fun persist() {
        val json = JSONObject()
            .put("created", dateCode).put("client", client).put("address", address)
            .put("photos", photos).put("notes", JSONArray(notes))
            .put("vatRate", vatRate ?: JSONObject.NULL)
        val rows = JSONArray()
        works.forEach {
            rows.put(JSONObject().put("description", it.description).put("unit", it.unit)
                .put("quantity", it.quantity ?: JSONObject.NULL)
                .put("price", it.price ?: JSONObject.NULL))
        }
        json.put("works", rows)
        File(dir, "inspeccion.json").writeText(json.toString(2))
    }

    fun makePdf(): File {
        val output = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "MoreThanTiles-Offerte-" + dateCode + ".pdf")
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var page: PdfDocument.Page? = null
        var y = 0f
        var n = 0
        val black = Color.rgb(25, 25, 25)
        val gold = Color.rgb(175, 132, 52)
        fun newPage() {
            page?.let { pdf.finishPage(it) }
            n++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            val canvas = page!!.canvas
            canvas.drawColor(Color.WHITE)
            paint.color = black; paint.textSize = 21f; paint.isFakeBoldText = true
            canvas.drawText("MORETHANTILES", 42f, 58f, paint)
            paint.color = gold; paint.strokeWidth = 3f
            canvas.drawLine(42f, 68f, 553f, 68f, paint)
            paint.isFakeBoldText = false
            y = 96f
        }
        fun draw(text: String, size: Float = 11f, bold: Boolean = false) {
            if (y > 780) newPage()
            val c = page!!.canvas
            paint.color = black; paint.textSize = size; paint.isFakeBoldText = bold
            var row = ""
            text.split(" ").forEach { word ->
                val next = if (row.isEmpty()) word else row + " " + word
                if (paint.measureText(next) > 490f && row.isNotEmpty()) {
                    c.drawText(row, 42f, y, paint)
                    y += size + 6f
                    if (y > 780) newPage()
                    row = word
                } else row = next
            }
            if (row.isNotBlank()) { page!!.canvas.drawText(row, 42f, y, paint); y += size + 9f }
        }
        newPage()
        val official = readyForOfficial()
        draw(if (official) "OFFERTE - ALLEEN ARBEIDSLOON" else "CONCEPT - ONVOLLEDIGE OFFERTE", 15f, true)
        draw("Datum: " + SimpleDateFormat("dd-MM-yyyy", Locale("nl")).format(Date()))
        draw("Klant: " + client.ifBlank { "[nog invullen]" })
        draw("Adres: " + address.ifBlank { "[nog invullen]" })
        draw("Referentie: MTT-" + dateCode)
        y += 16f
        draw("WERKOMSCHRIJVING EN HOEVEELHEDEN", 12f, true)
        var total = 0.0
        var incomplete = false
        works.forEachIndexed { i, work ->
            draw((i + 1).toString() + ". " + work.description, 11f, true)
            val qty = work.quantity?.let { String.format(Locale.GERMANY, "%.2f", it) } ?: "n.t.b."
            val cost = work.amount()
            if (cost == null) incomplete = true else total += cost
            val euros = cost?.let { String.format(Locale.GERMANY, "%.2f", it) + " EUR" } ?: "n.t.b."
            val unitPrice = work.price?.let { String.format(Locale.GERMANY, "%.2f", it) } ?: "n.t.b."
            draw("    " + qty + " " + work.unit + " x EUR " + unitPrice + " = " + euros)
        }
        if (works.isEmpty()) { incomplete = true; draw("[Nog geen werkposten bevestigd]") }
        y += 12f
        draw("Totaal bekende arbeidskosten: EUR " + String.format(Locale.GERMANY, "%.2f", total), 13f, true)
        val vat = vatRate?.let { total * it / 100.0 }
        if (vat == null) draw("BTW: tarief en toepasselijkheid nog te bevestigen.")
        else {
            draw("BTW (" + String.format(Locale.GERMANY, "%.1f", vatRate) + "%): EUR " +
                String.format(Locale.GERMANY, "%.2f", vat))
            draw("Totaal inclusief BTW: EUR " + String.format(Locale.GERMANY, "%.2f", total + vat), 13f, true)
        }
        if (incomplete || !official) draw("LET OP: Concept. Ontbrekende hoeveelheden of tarieven moeten worden bevestigd.")
        y += 14f
        draw("Voorwaarden: Alleen arbeidsloon, tenzij schriftelijk anders overeengekomen.")
        draw("Materialen, extra werkzaamheden en eventuele BTW zijn niet inbegrepen.")
        draw("Geldigheid: 30 dagen vanaf offertedatum.")
        y += 15f
        val sf = signatureFile()
        if (signatureAllowed && sf.exists()) {
            val image = BitmapFactory.decodeByteArray(sf.readBytes(), 0, sf.length().toInt())
            if (image != null) {
                if (y > 620f) newPage()
                draw("Handtekening MoreThanTiles (door eigenaar goedgekeurd):")
                val scale = minOf(190f / image.width, 78f / image.height)
                val rect = RectF(45f, y, 45f + image.width * scale, y + image.height * scale)
                page!!.canvas.drawBitmap(image, null, rect, paint)
                y += 90f; image.recycle()
            } else draw("Handtekening niet beschikbaar: onleesbaar afbeeldingsbestand.")
        } else draw("Handtekening: nog niet door eigenaar geautoriseerd.")
        draw("MoreThanTiles - Amsterdam")
        page?.let { pdf.finishPage(it) }
        output.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return output
    }
}
