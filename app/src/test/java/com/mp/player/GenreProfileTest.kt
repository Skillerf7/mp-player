package com.mp.player

import com.mp.player.ai.GenreProfile
import com.mp.player.ai.IntentEngine
import com.mp.player.ai.QueueRequest
import com.mp.player.ai.Recommender
import com.mp.player.ai.TrackSimilarity
import com.mp.player.ai.TrackInfo
import com.mp.player.ai.UserIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreProfileTest {
    private fun info(id:Long, title:String, artist:String, genre:String="", bpm:Float=80f): TrackInfo {
        val t=Track("content://g/$id", title, artist, artist, "A", 2020, 180000, "Music", id, genre)
        val a=AnalysisResult(t.uri,0,bpm,-10f,-1f,-20f,44100,2,false)
        return TrackInfo(t,a,null,0,false)
    }

    @Test fun rapPlaylistOverridesMissingGenreAndBpm() {
        val a=info(1,"EURE WELT","@LUVRE47",bpm=170f)
        val b=info(2,"Sommergewitter","OG Keemo",bpm=80f)
        val c=info(3,"Other","Random",bpm=170f)
        val profile=GenreProfile.build(listOf(a,b,c), mapOf("Chill rap" to listOf(a.track,b.track)))
        assertTrue(profile.evidence(a,"Rap").confidence >= .94f)
        assertTrue(profile.evidence(b,"Rap").confidence >= .94f)
        assertTrue(profile.evidence(c,"Rap").confidence < .68f)
    }

    @Test fun explicitTagWinsAndPlaylistIsAdditionalEvidence() {
        val a=info(1,"Track","Artist",genre="Deutschrap",bpm=60f)
        val profile=GenreProfile.build(listOf(a), emptyMap())
        val e=profile.evidence(a,"Rap")
        assertEquals(1f,e.confidence,.001f)
        assertTrue(e.reasons.contains("Genre-Tag"))
    }

    @Test fun rapRequestUsesPlaylistEvidenceInsteadOfBpm() {
        val rapSlow=info(1,"Rap Slow","Sido",bpm=80f)
        val rapFast=info(2,"Rap Fast","Kontra K",bpm=170f)
        val technoFast=info(3,"Fast","Other",genre="Techno",bpm=170f)
        val infos=listOf(rapSlow,rapFast,technoFast)
        val profile=GenreProfile.build(infos,mapOf("Power Rap" to listOf(rapSlow.track,rapFast.track)))
        val result=Recommender.build(infos,QueueRequest(null,genres=listOf("rap"),genreProfile=profile,maxTracks=10,seed=7))
        assertTrue(result.tracks.isNotEmpty())
        assertTrue(result.tracks.all { it.title.startsWith("Rap") })
        assertTrue(TrackSimilarity.score(rapSlow,rapFast,profile) > TrackSimilarity.score(rapSlow,technoFast,profile))
    }

    @Test fun playlistReferenceIntentIsLocalAndStructured() {
        assertEquals(UserIntent.SimilarToPlaylist("schillah"), IntentEngine.understand("Mach mir wieder sowas wie meine Schillah-Playlist"))
    }
}
