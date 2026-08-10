import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import PodcastEmbedBar from "@/components/podcast/PodcastEmbedBar";
import { podcastEmbedUrl } from "@/lib/articleBodyFormat";
import { resolveEpisodePlayUrl } from "@/lib/podcastAudio";

const PodcastPlayerContext = createContext(null);

const SKIP_SECONDS = 15;
const EMBED_BODY_CLASS = "podcast-embed-active";
const EMBED_MIN_BODY_CLASS = "podcast-embed-minimized";

export function PodcastPlayerProvider({ children }) {
  const audioRef = useRef(null);
  const [episode, setEpisode] = useState(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [loading, setLoading] = useState(false);
  const [embedMinimized, setEmbedMinimized] = useState(false);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return undefined;
    const onTime = () => setCurrentTime(audio.currentTime || 0);
    const onDuration = () => setDuration(Number.isFinite(audio.duration) ? audio.duration : 0);
    const onPlay = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onWaiting = () => setLoading(true);
    const onCanPlay = () => setLoading(false);
    const onEnded = () => {
      setIsPlaying(false);
      setCurrentTime(0);
    };
    audio.addEventListener("timeupdate", onTime);
    audio.addEventListener("durationchange", onDuration);
    audio.addEventListener("loadedmetadata", onDuration);
    audio.addEventListener("play", onPlay);
    audio.addEventListener("pause", onPause);
    audio.addEventListener("waiting", onWaiting);
    audio.addEventListener("canplay", onCanPlay);
    audio.addEventListener("ended", onEnded);
    return () => {
      audio.removeEventListener("timeupdate", onTime);
      audio.removeEventListener("durationchange", onDuration);
      audio.removeEventListener("loadedmetadata", onDuration);
      audio.removeEventListener("play", onPlay);
      audio.removeEventListener("pause", onPause);
      audio.removeEventListener("waiting", onWaiting);
      audio.removeEventListener("canplay", onCanPlay);
      audio.removeEventListener("ended", onEnded);
    };
  }, []);

  useEffect(() => {
    document.body.classList.remove(EMBED_BODY_CLASS, EMBED_MIN_BODY_CLASS);
    if (episode?.playerMode === "embed") {
      document.body.classList.add(EMBED_BODY_CLASS);
      if (embedMinimized) document.body.classList.add(EMBED_MIN_BODY_CLASS);
    }
    return () => document.body.classList.remove(EMBED_BODY_CLASS, EMBED_MIN_BODY_CLASS);
  }, [episode?.playerMode, embedMinimized]);

  const stopAudio = useCallback(() => {
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
    }
  }, []);

  const playEpisode = useCallback(async (nextEpisode) => {
    const audioUrl = resolveEpisodePlayUrl(nextEpisode?.audioUrl || nextEpisode?.audio_url);
    if (!audioUrl) return false;
    const audio = audioRef.current;
    if (!audio) return false;
    stopAudio();
    const normalized = {
      id: String(nextEpisode.id || audioUrl),
      title: String(nextEpisode.title || "Podcast").trim(),
      subtitle: String(nextEpisode.subtitle || nextEpisode.feed_title || nextEpisode.feedTitle || "").trim(),
      audioUrl,
      pageUrl: String(nextEpisode.pageUrl || nextEpisode.page_url || nextEpisode.external_url || "").trim(),
      playerMode: "audio",
    };
    setEpisode(normalized);
    setLoading(true);
    setIsPlaying(false);
    setCurrentTime(0);
    setDuration(0);
    if (audio.src !== audioUrl) {
      audio.src = audioUrl;
      audio.load();
    }
    try {
      await audio.play();
      return true;
    } catch {
      setLoading(false);
      setIsPlaying(false);
      return false;
    }
  }, [stopAudio]);

  const playEmbedEpisode = useCallback(
    (nextEpisode) => {
      const sourceUrl = String(
        nextEpisode?.external_url || nextEpisode?.externalUrl || nextEpisode?.page_url || nextEpisode?.pageUrl || "",
      ).trim();
      const embedUrl = podcastEmbedUrl(sourceUrl);
      if (!embedUrl) return false;
      stopAudio();
      setEmbedMinimized(false);
      setEpisode({
        id: String(nextEpisode.id || sourceUrl),
        title: String(nextEpisode.title || "Podcast").trim(),
        subtitle: String(nextEpisode.subtitle || nextEpisode.feed_title || nextEpisode.feedTitle || "").trim(),
        embedUrl,
        pageUrl: sourceUrl,
        playerMode: "embed",
      });
      setLoading(false);
      setIsPlaying(true);
      setCurrentTime(0);
      setDuration(0);
      return true;
    },
    [stopAudio],
  );

  const togglePlayPause = useCallback(async () => {
    const audio = audioRef.current;
    if (!audio || !episode || episode.playerMode !== "audio") return;
    if (audio.paused) {
      try {
        await audio.play();
      } catch {
        /* ignore */
      }
    } else {
      audio.pause();
    }
  }, [episode]);

  const seek = useCallback((seconds) => {
    const audio = audioRef.current;
    if (!audio || !Number.isFinite(seconds)) return;
    const max = Number.isFinite(audio.duration) ? audio.duration : seconds;
    audio.currentTime = Math.min(Math.max(0, seconds), max);
    setCurrentTime(audio.currentTime);
  }, []);

  const skip = useCallback(
    (delta) => {
      const audio = audioRef.current;
      if (!audio) return;
      seek((audio.currentTime || 0) + delta);
    },
    [seek],
  );

  const toggleEmbedMinimized = useCallback(() => {
    setEmbedMinimized((v) => !v);
  }, []);

  const stop = useCallback(() => {
    stopAudio();
    setEmbedMinimized(false);
    setEpisode(null);
    setIsPlaying(false);
    setCurrentTime(0);
    setDuration(0);
    setLoading(false);
  }, [stopAudio]);

  const value = useMemo(
    () => ({
      episode,
      isPlaying,
      loading,
      currentTime,
      duration,
      playEpisode,
      playEmbedEpisode,
      embedMinimized,
      toggleEmbedMinimized,
      togglePlayPause,
      seek,
      skip,
      stop,
    }),
    [episode, isPlaying, loading, currentTime, duration, embedMinimized, playEpisode, playEmbedEpisode, toggleEmbedMinimized, togglePlayPause, seek, skip, stop],
  );

  return (
    <PodcastPlayerContext.Provider value={value}>
      {children}
      <audio ref={audioRef} preload="metadata" className="hidden" aria-hidden />
      <PodcastEmbedBar />
    </PodcastPlayerContext.Provider>
  );
}

export function usePodcastPlayer() {
  const ctx = useContext(PodcastPlayerContext);
  if (!ctx) {
    throw new Error("usePodcastPlayer must be used within PodcastPlayerProvider");
  }
  return ctx;
}

export function usePodcastPlayerOptional() {
  return useContext(PodcastPlayerContext);
}

export { SKIP_SECONDS };
