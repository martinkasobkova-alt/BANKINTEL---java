import { canPlayEpisodeInBackground, resolveDirectAudioUrl, resolveEpisodePlayUrl } from "./podcastAudio";

describe("resolveDirectAudioUrl", () => {
  it("accepts mp3 URLs", () => {
    expect(resolveDirectAudioUrl("https://cdn.example.com/ep.mp3")).toBe("https://cdn.example.com/ep.mp3");
  });

  it("accepts mp4 URLs", () => {
    expect(resolveDirectAudioUrl("https://cdn.example.com/ep.mp4")).toBe("https://cdn.example.com/ep.mp4");
  });

  it("rejects spotify links", () => {
    expect(resolveDirectAudioUrl("https://open.spotify.com/episode/abc")).toBeNull();
  });
});

describe("resolveEpisodePlayUrl", () => {
  it("maps API stream paths to absolute URLs", () => {
    const prev = window.location;
    delete window.location;
    window.location = { origin: "https://app.test" };
    expect(resolveEpisodePlayUrl("/api/podcasts/episodes/abc/audio")).toBe(
      "https://app.test/api/podcasts/episodes/abc/audio",
    );
    window.location = prev;
  });
});

describe("canPlayEpisodeInBackground", () => {
  it("allows background mode with api audio", () => {
    expect(canPlayEpisodeInBackground({ play_mode: "background", audio_url: "/api/podcasts/episodes/x/audio" })).toBe(
      true,
    );
  });

  it("blocks embed-only episodes", () => {
    expect(canPlayEpisodeInBackground({ play_mode: "embed", external_url: "https://open.spotify.com/episode/x" })).toBe(
      false,
    );
  });
});
