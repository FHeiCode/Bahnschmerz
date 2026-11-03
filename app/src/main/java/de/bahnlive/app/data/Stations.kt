package de.bahnlive.app.data

/** Geografische Position (WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Große Bahnhöfe mit ungefähren Koordinaten. Sie dienen als Eingabevorschläge und als
 * sofort verfügbare Kartenpunkte. Alle anderen Bahnhöfe sucht [StationLocator] bei Bedarf.
 * Eingegeben werden kann jeder Bahnhofsname, jede EVA-Nummer (8000105) oder DS100-Kürzel (FF).
 */
object Stations {
    val known: Map<String, LatLon> = linkedMapOf(
        "Aachen Hbf" to LatLon(50.7678, 6.0912),
        "Augsburg Hbf" to LatLon(48.3655, 10.8855),
        "Berlin Hbf" to LatLon(52.5251, 13.3694),
        "Berlin Gesundbrunnen" to LatLon(52.5487, 13.3885),
        "Berlin Ostbahnhof" to LatLon(52.5103, 13.4344),
        "Berlin Südkreuz" to LatLon(52.4753, 13.3657),
        "Berlin-Spandau" to LatLon(52.5345, 13.1975),
        "Bielefeld Hbf" to LatLon(52.0291, 8.5325),
        "Bochum Hbf" to LatLon(51.4789, 7.2233),
        "Bonn Hbf" to LatLon(50.7320, 7.0971),
        "Braunschweig Hbf" to LatLon(52.2525, 10.5397),
        "Bremen Hbf" to LatLon(53.0832, 8.8136),
        "Darmstadt Hbf" to LatLon(49.8725, 8.6294),
        "Dortmund Hbf" to LatLon(51.5178, 7.4592),
        "Dresden Hbf" to LatLon(51.0405, 13.7320),
        "Dresden-Neustadt" to LatLon(51.0654, 13.7409),
        "Duisburg Hbf" to LatLon(51.4297, 6.7757),
        "Düsseldorf Hbf" to LatLon(51.2199, 6.7943),
        "Düsseldorf Flughafen" to LatLon(51.2922, 6.7866),
        "Erfurt Hbf" to LatLon(50.9726, 11.0382),
        "Essen Hbf" to LatLon(51.4514, 7.0136),
        "Frankfurt(Main)Hbf" to LatLon(50.1071, 8.6638),
        "Frankfurt(M) Flughafen Fernbf" to LatLon(50.0532, 8.5703),
        "Frankfurt(Main)Süd" to LatLon(50.0996, 8.6866),
        "Freiburg(Breisgau) Hbf" to LatLon(47.9977, 7.8411),
        "Fulda" to LatLon(50.5546, 9.6840),
        "Gelsenkirchen Hbf" to LatLon(51.5050, 7.1022),
        "Göttingen" to LatLon(51.5365, 9.9268),
        "Hagen Hbf" to LatLon(51.3626, 7.4609),
        "Halle(Saale)Hbf" to LatLon(51.4774, 11.9870),
        "Hamburg Hbf" to LatLon(53.5530, 10.0067),
        "Hamburg Dammtor" to LatLon(53.5605, 9.9895),
        "Hamburg-Altona" to LatLon(53.5520, 9.9350),
        "Hamburg-Harburg" to LatLon(53.4562, 9.9915),
        "Hamm(Westf)Hbf" to LatLon(51.6784, 7.8079),
        "Hanau Hbf" to LatLon(50.1210, 8.9290),
        "Hannover Hbf" to LatLon(52.3767, 9.7410),
        "Heidelberg Hbf" to LatLon(49.4036, 8.6757),
        "Ingolstadt Hbf" to LatLon(48.7449, 11.4370),
        "Karlsruhe Hbf" to LatLon(48.9935, 8.4019),
        "Kassel-Wilhelmshöhe" to LatLon(51.3130, 9.4467),
        "Kiel Hbf" to LatLon(54.3148, 10.1320),
        "Koblenz Hbf" to LatLon(50.3510, 7.5890),
        "Köln Hbf" to LatLon(50.9430, 6.9589),
        "Köln Messe/Deutz" to LatLon(50.9406, 6.9746),
        "Leipzig Hbf" to LatLon(51.3454, 12.3820),
        "Lübeck Hbf" to LatLon(53.8676, 10.6697),
        "Magdeburg Hbf" to LatLon(52.1305, 11.6268),
        "Mainz Hbf" to LatLon(50.0013, 8.2587),
        "Mannheim Hbf" to LatLon(49.4794, 8.4697),
        "Münster(Westf)Hbf" to LatLon(51.9566, 7.6352),
        "München Hbf" to LatLon(48.1402, 11.5600),
        "München Ost" to LatLon(48.1272, 11.6047),
        "München-Pasing" to LatLon(48.1499, 11.4613),
        "Nürnberg Hbf" to LatLon(49.4459, 11.0822),
        "Offenbach(Main)Hbf" to LatLon(50.0995, 8.7600),
        "Oldenburg(Oldb)Hbf" to LatLon(53.1446, 8.2224),
        "Osnabrück Hbf" to LatLon(52.2725, 8.0617),
        "Potsdam Hbf" to LatLon(52.3916, 13.0666),
        "Regensburg Hbf" to LatLon(49.0119, 12.0995),
        "Rostock Hbf" to LatLon(54.0782, 12.1311),
        "Saarbrücken Hbf" to LatLon(49.2410, 6.9910),
        "Schwerin Hbf" to LatLon(53.6343, 11.4078),
        "Siegburg/Bonn" to LatLon(50.7941, 7.2027),
        "Stuttgart Hbf" to LatLon(48.7840, 9.1817),
        "Trier Hbf" to LatLon(49.7570, 6.6525),
        "Ulm Hbf" to LatLon(48.3995, 9.9827),
        "Wiesbaden Hbf" to LatLon(50.0707, 8.2435),
        "Wolfsburg Hbf" to LatLon(52.4295, 10.7877),
        "Wuppertal Hbf" to LatLon(51.2543, 7.1497),
        "Würzburg Hbf" to LatLon(49.8018, 9.9358),
    )

    val suggestions: List<String> = known.keys.toList()

    private val knownNormalized: Map<String, LatLon> =
        known.mapKeys { RouteRepository.normalize(it.key) }

    fun coordinatesOf(name: String): LatLon? =
        knownNormalized[RouteRepository.normalize(name)]
            ?: known.entries.firstOrNull { RouteRepository.sameStation(it.key, name) }?.value

    fun filter(query: String, limit: Int = 6): List<String> {
        val q = RouteRepository.normalize(query)
        if (q.length < 2) return emptyList()
        return suggestions
            .filter { RouteRepository.normalize(it).let { n -> n.startsWith(q) || n.contains(" $q") } }
            .filterNot { RouteRepository.normalize(it) == q }
            .take(limit)
    }
}
