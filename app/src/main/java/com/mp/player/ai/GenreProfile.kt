package com.mp.player.ai

import com.mp.player.Track

data class GenreEvidence(val genre: String, val confidence: Float, val reasons: List<String>)

data class GenreProfile(val byUri: Map<String, List<GenreEvidence>>, val artistVotes: Map<String, Map<String, Float>>, val personal: PersonalSignals? = null, val mlByUri: Map<String, List<GenreEvidence>> = emptyMap()) {
    fun evidence(info: TrackInfo, genre: String): GenreEvidence {
        val key = normalizeGenre(genre)
        // 1) Explizite Benutzerkorrektur hat höchste Priorität
        val userGenre = personal?.genreOverride?.get(info.track.uri)
        val userMatch = if (userGenre != null && normalizeGenre(userGenre) == key) 1f else 0f
        val explicit = if (userMatch >= 1f) 0f else explicitGenreScore(info.track, key)
        val fromPlaylist = byUri[info.track.uri].orEmpty().firstOrNull { normalizeGenre(it.genre) == key }
        val artist = artistVotes[artistKey(info.track.artist)]?.get(key) ?: 0f
        val ml = mlByUri[info.track.uri].orEmpty().firstOrNull { normalizeGenre(it.genre) == key }
        val score = maxOf(userMatch, explicit, fromPlaylist?.confidence ?: 0f, artist, ml?.confidence ?: 0f).coerceIn(0f, 1f)
        val reasons = buildList {
            if (userMatch >= 1f) add("Benutzer-Genre-Korrektur")
            if (explicit >= 0.9f) add("Genre-Tag")
            fromPlaylist?.reasons?.forEach { if (it !in this) add(it) }
            if (artist >= 0.55f) add("Artist aus persönlichen ${genre}-Playlists")
            // ML nur nennen, wenn es wirklich beiträgt (niedrigster Rang)
            if (ml != null && ml.confidence >= maxOf(userMatch, explicit, fromPlaylist?.confidence ?: 0f, artist)) ml.reasons.forEach { if (it !in this) add(it) }
        }
        return GenreEvidence(key, score, reasons)
    }
    fun matches(info: TrackInfo, requested: List<String>): Float = requested.maxOfOrNull { evidence(info, it).confidence } ?: 0f

    /** Genre-Zuordnungen mit Herkunft – Tag ≠ Audio-Erkennung. */
    fun attributions(info: TrackInfo): List<GenreAttribution> {
        val out = ArrayList<GenreAttribution>()
        val user = personal?.genreOverride?.get(info.track.uri)
        if (user != null) out += GenreAttribution(normalizeGenre(user), 1f, GenreSource.USER_CORRECTION, listOf("Benutzer-Korrektur"))
        val tag = normalizeGenre(info.track.genre)
        if (tag.isNotBlank()) out += GenreAttribution(tag, 0.9f, GenreSource.FILE_TAG, listOf("Dateitag"))
        byUri[info.track.uri].orEmpty().forEach { e ->
            out += GenreAttribution(e.genre, e.confidence, GenreSource.PLAYLIST, e.reasons)
        }
        mlByUri[info.track.uri].orEmpty().forEach { e ->
            out += GenreAttribution(e.genre, e.confidence, GenreSource.AUDIO_ML, e.reasons)
        }
        return out.distinctBy { it.genre + it.source.name }
    }

    companion object {
        private val ALIASES = mapOf(
            "rap" to setOf("rap", "hiphop", "hip hop", "hip-hop", "deutschrap", "trap"),
            "techno" to setOf("techno", "schranz"), "hardtekk" to setOf("hardtekk", "hardtek", "hard tek", "tekk", "hardtech"),
            "hardcore" to setOf("hardcore", "gabber", "frenchcore", "uptempo", "speedcore"), "hardstyle" to setOf("hardstyle", "rawstyle"),
            "drum & bass" to setOf("dnb", "drum and bass", "drum & bass", "drum n bass", "jungle"),
            "rock" to setOf("rock"), "metal" to setOf("metal"), "pop" to setOf("pop"), "house" to setOf("house"),
            "trance" to setOf("trance"), "jazz" to setOf("jazz"), "klassik" to setOf("klassik", "classical"),
            "ambient" to setOf("ambient"), "lo-fi" to setOf("lofi", "lo-fi", "lo fi"), "reggae" to setOf("reggae"),
            "electro" to setOf("electro", "edm"), "punk" to setOf("punk"), "folk" to setOf("folk")
        )
        fun normalizeGenre(raw: String): String { val n=TextUtil.norm(raw).trim(); return ALIASES.entries.firstOrNull { n in it.value || n==it.key }?.key ?: n }
        private fun explicitGenreScore(t: Track, wanted: String): Float = if (normalizeGenre(t.genre) == wanted) 1f else 0f
        private fun artistKey(a: String) = TextUtil.norm(a).replace(Regex("\\s+"), " ").trim()
        private fun playlistGenre(name: String): String? { val n=TextUtil.norm(name); return ALIASES.entries.firstOrNull { (_,a)->a.any { n.contains(it) } }?.key }
        private fun splitArtists(raw: String) = TextUtil.norm(raw).split(Regex("\\s+(?:x|feat\\.?|ft\\.?|featuring|&|and)\\s+|[,;/]")).map{it.trim()}.filter{it.length>=2}

        fun build(infos: List<TrackInfo>, playlists: Map<String,List<Track>>, personal: PersonalSignals? = null, mlGenres: Map<String, String> = emptyMap()): GenreProfile {
            val byUri=HashMap<String,MutableMap<String,GenreEvidence>>(); val counts=HashMap<String,MutableMap<String,Float>>(); val all=infos.associateBy{it.track.uri}
            fun add(uri:String,g0:String,c:Float,r:String){ val g=normalizeGenre(g0); if(g.isBlank())return; val m=byUri.getOrPut(uri){HashMap()}; val old=m[g]; m[g]=GenreEvidence(g,maxOf(old?.confidence?:0f,c).coerceIn(0f,1f),(old?.reasons.orEmpty()+r).distinct()) }
            infos.forEach{ val g=normalizeGenre(it.track.genre); if(g.isNotBlank()) add(it.track.uri,g,1f,"Genre-Tag") }
            playlists.forEach{(name,tracks)-> val pg=playlistGenre(name)?:return@forEach; val w=if(TextUtil.norm(name).contains("power")) .98f else if(TextUtil.norm(name).contains("chill")) .95f else .90f; tracks.forEach{t->add(t.uri,pg,w,"Playlist \"$name\""); splitArtists(t.artist).forEach{a->val m=counts.getOrPut(artistKey(a)){HashMap()};m[pg]=(m[pg]?:0f)+1f}}}
            val artistVotes=counts.mapValues{(_,v)->val total=v.values.sum().coerceAtLeast(1f);v.mapValues{(_,c)->(0.55f+0.40f*c/total).coerceAtMost(.95f)}}
            all.values.forEach{info->artistVotes[artistKey(info.track.artist)].orEmpty().forEach{(g,c)->if(c>=.70f)add(info.track.uri,g,c,"Artist in persönlichen ${g}-Playlists")}}
            return GenreProfile(byUri.mapValues{it.value.values.toList()},artistVotes,personal,MlGenre.toEvidence(mlGenres))
        }
    }
}

data class PlaylistProfile(val name:String,val tracks:List<TrackInfo>,val dominantGenres:List<Pair<String,Float>>,val artists:List<Pair<String,Int>>,val averageBpm:Float?,val averageEnergy:Float?,val moods:Map<Mood,Float>,val averageDurationMs:Long?) {
    companion object { fun build(name:String,tracks:List<TrackInfo>,genres:GenreProfile):PlaylistProfile {
        val gs=HashMap<String,MutableList<Float>>(); tracks.forEach{(genres.byUri[it.track.uri].orEmpty()+genres.mlByUri[it.track.uri].orEmpty()).forEach{e->gs.getOrPut(e.genre){mutableListOf()}.add(e.confidence)}}
        val dom=gs.mapValues{it.value.average().toFloat()}.entries.sortedByDescending{it.value}.map{it.key to it.value}; val arts=tracks.groupingBy{it.track.artist}.eachCount().entries.sortedByDescending{it.value}.take(20).map{it.key to it.value}; val bp=tracks.mapNotNull{it.analysis?.bpm?.takeIf{b->b>0}}; val en=tracks.mapNotNull{Recommender.energy(it.analysis)}
        val moods=Mood.values().associateWith{m->tracks.map{Recommender.moodScore(it,m)}.average().toFloat().coerceIn(0f,1f)}.filterValues{it>=.45f}; val dur=tracks.map{it.track.durationMs}.filter{it>0}.takeIf{it.isNotEmpty()}?.average()?.toLong()
        return PlaylistProfile(name,tracks,dom,arts,bp.takeIf{it.isNotEmpty()}?.average()?.toFloat(),en.takeIf{it.isNotEmpty()}?.average()?.toFloat(),moods,dur)
    }}
}
