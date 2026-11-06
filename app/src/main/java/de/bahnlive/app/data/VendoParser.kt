package de.bahnlive.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Bahnhof aus der DB-Schnittstelle (DB Navigator, „vendo“). */
data class VendoStation(val name: String, val eva: String, val position: LatLon?)

/** Zeile einer Ankunfts- oder Abfahrtstafel. */
data class VendoBoardEntry(val tripId: String, val trainNumber: String?, val name: String?)

/** Halt im Fahrtverlauf mit Plan- und Echtzeit. */
data class VendoStop(
    val name: String,
    val eva: String?,
    val position: LatLon?,
    val arrivalPlanned: LocalDateTime?,
    val arrivalRealtime: LocalDateTime?,
    val departurePlanned: LocalDateTime?,
    val departureRealtime: LocalDateTime?,
    val cancelled: Boolean,
)

/** Kompletter Fahrtverlauf eines Zuges. */
data class VendoTrip(
    val name: String?,
    val trainNumber: String?,
    val stops: List<VendoStop>,
    /** Linienverlauf des Zuges (Gleisgeometrie der DB), leer wenn nicht geliefert. */
    val polyline: List<LatLon> = emptyList(),
)

/**
 * Liest die Antworten der DB-Navigator-Schnittstelle. Das Format ist nicht offiziell
 * dokumentiert; es folgt der Open-Source-Bibliothek db-vendo-client. Alles wird tolerant
 * gelesen: Fehlende oder unerwartete Felder führen zu null statt zu einem Absturz.
 */
object VendoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val trainNumberInId = Regex("#ZE#([^#]+)#")

    fun parseLocations(body: String): List<VendoStation> {
        val root = json.parseToJsonElement(body)
        val list = (root as? JsonArray) ?: (root as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray
            ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val lid = o.text("locationId") ?: o.text("id")
            val eva = o.text("evaNr") ?: o.text("extId") ?: lid?.let { lidPart(it, "L") } ?: return@mapNotNull null
            val name = o.text("name") ?: lid?.let { lidPart(it, "O") } ?: return@mapNotNull null
            VendoStation(name, eva.trimStart('0'), position(o, lid))
        }
    }

    /** Liest Ankunfts- oder Abfahrtstafel. */
    fun parseBoard(body: String): List<VendoBoardEntry> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val list = (root["bahnhofstafelAnkunftPositionen"] ?: root["bahnhofstafelAbfahrtPositionen"]
            ?: root["items"]) as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.text("zuglaufId") ?: o.text("journeyId") ?: return@mapNotNull null
            val number = trainNumberInId.find(id)?.groupValues?.get(1)
                ?: o.text("verkehrsmittelNummer") ?: o.text("zugnummer")
            VendoBoardEntry(id, number?.trim(), o.text("mitteltext") ?: o.text("langtext"))
        }
    }

    fun parseTrip(body: String, zone: ZoneId = ZoneId.systemDefault()): VendoTrip? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val halte = root["halte"] as? JsonArray ?: return null
        val stops = halte.mapNotNull { el ->
            val h = el as? JsonObject ?: return@mapNotNull null
            val ort = h["ort"] as? JsonObject
            val lid = ort?.text("locationId")
            val name = ort?.text("name") ?: lid?.let { lidPart(it, "O") } ?: return@mapNotNull null
            VendoStop(
                name = name,
                eva = ort?.text("evaNr") ?: lid?.let { lidPart(it, "L") },
                position = ort?.let { position(it, lid) },
                arrivalPlanned = time(h.text("ankunftsDatum"), zone),
                arrivalRealtime = time(h.text("ezAnkunftsDatum"), zone),
                departurePlanned = time(h.text("abgangsDatum"), zone),
                departureRealtime = time(h.text("ezAbgangsDatum"), zone),
                cancelled = cancelled(h),
            )
        }
        return VendoTrip(
            name = root.text("mitteltext") ?: root.text("langtext"),
            trainNumber = root.text("verkehrsmittelNummer"),
            stops = stops,
            polyline = polyline(root),
        )
    }

    /** polylineGroup.polylineDesc[].coordinates[{latitude, longitude}] */
    private fun polyline(root: JsonObject): List<LatLon> {
        val desc = ((root["polylineGroup"] as? JsonObject)?.get("polylineDesc") as? JsonArray) ?: return emptyList()
        return desc.flatMap { d ->
            ((d as? JsonObject)?.get("coordinates") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val lat = (o["latitude"] as? JsonPrimitive)?.doubleOrNull
                val lon = (o["longitude"] as? JsonPrimitive)?.doubleOrNull
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }
        }
    }

    private fun time(text: String?, zone: ZoneId): LocalDateTime? = text?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
    }

    private fun cancelled(o: JsonObject): Boolean {
        if (listOf("cancelled", "canceled", "ausfall").any { (o[it] as? JsonPrimitive)?.booleanOrNull == true }) return true
        val notes = listOf("echtzeitNotizen", "risNotizen").flatMap { (o[it] as? JsonArray).orEmpty() }
        return notes.any { n ->
            val no = n as? JsonObject ?: return@any false
            no.text("key") == "text.realtime.stop.cancelled" || no.text("type") == "HALT_AUSFALL" ||
                no.text("text")?.let { it == "Halt entfällt" || it.contains("fällt aus") } == true
        }
    }

    private fun position(o: JsonObject, lid: String?): LatLon? {
        (o["position"] as? JsonObject ?: o["coordinates"] as? JsonObject)?.let { p ->
            val lat = (p["latitude"] as? JsonPrimitive)?.doubleOrNull
            val lon = (p["longitude"] as? JsonPrimitive)?.doubleOrNull
            if (lat != null && lon != null) return LatLon(lat, lon)
        }
        val x = lid?.let { lidPart(it, "X") }?.toDoubleOrNull()
        val y = lid?.let { lidPart(it, "Y") }?.toDoubleOrNull()
        return if (x != null && y != null) LatLon(y / 1_000_000, x / 1_000_000) else null
    }

    /** "A=1@O=Name@X=…@L=8000105@" → Wert zu [key]. */
    private fun lidPart(lid: String, key: String): String? =
        lid.split('@').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    private fun JsonObject.text(key: String): String? = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }
}
