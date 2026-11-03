import assert from "node:assert/strict";
import { youtubeVideoId, youtubeLinkAt } from "./youtube.js";

assert.equal(youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"), "dQw4w9WgXcQ");
assert.equal(youtubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=x"), "dQw4w9WgXcQ");
assert.equal(youtubeVideoId("https://www.youtube.com/shorts/abcdefghijk"), "abcdefghijk");
assert.equal(youtubeVideoId("https://example.com/watch?v=x"), null);
assert.equal(youtubeLinkAt("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5", 83.7), "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=83s");
assert.equal(youtubeLinkAt("https://www.youtube.com/watch?v=abc123xyz00&list=PL1", 0), "https://www.youtube.com/watch?v=abc123xyz00&list=PL1");
assert.equal(youtubeLinkAt("https://music.youtube.com/watch?v=abc123xyz00", 10), "https://music.youtube.com/watch?v=abc123xyz00&t=10s");
assert.equal(youtubeLinkAt("https://example.com/", 10), "https://example.com/");
console.log("youtube.js: all tests passed");
