# SoundCloud

Melo reads SoundCloud's public catalog through **api-v2**, the API soundcloud.com uses itself. No account is involved and nothing is written to SoundCloud. Code: `data/soundcloud/` and `playback/SoundCloudDataSource.kt`.

Evidence levels follow [README.md](README.md). Unless stated otherwise, statements are **live** (observed against the real service in November 2026) and the parsers are **tested with doubles** in `SoundCloudParsingTest`.

## Client id

Every api-v2 request carries a `client_id` query parameter. It is the public id of the web player, not a developer registration:

1. Fetch `https://soundcloud.com/`, collect the `<script src>` addresses on `*.sndcdn.com`.
2. Read them from the last one backwards; the first `client_id:"<32 alphanumerics>"` (or `client_id=` in a URL) is the id.
3. Keep it on the device. On `401` or `403`, read it again once and retry the request.

## Catalog

| Request | Use |
| --- | --- |
| `GET /search?q=&limit=&linked_partitioning=1` | Search across tracks, playlists, albums and users; `next_href` gives the next page |
| `GET /search/tracks?q=&limit=` | Tracks only, to add to a playlist |
| `GET /resolve?url=<soundcloud.com address>` | A pasted link; `on.soundcloud.com` and `snd.sc` short links are followed first |
| `GET /tracks?ids=1,2,3` | Up to 50 full tracks |
| `GET /tracks/<id>` | One track, with fresh `media.transcodings` and `track_authorization` |
| `GET /playlists/<id>` | A playlist or album. Only the first few tracks are complete; the others are `{id, kind}` stubs, filled in with `/tracks?ids=` |
| `GET /users/<id>`, `/users/<id>/toptracks` (or `/tracks` when empty), `/albums`, `/relatedartists` | Artist pages |
| `GET /users/<id>/playlists_without_albums`, `/users/<id>/albums` | The linked profile's own playlists and albums |
| `GET /users/<id>/likes` | A profile's public likes; each item wraps a `track` or a `playlist` |
| `GET /stations/soundcloud:track-stations:<id>/tracks`, `/tracks/<id>/related` | Radio from a track |

Responses use `kind` (`track`, `playlist`, `user`) to tell entities apart. A playlist with `is_album` or a `set_type` of `album`, `ep`, `single` or `compilation` is shown as an album. JSON `null` fields are common and must not be read as the text `"null"`.

Melo ids: `sc:track:<id>`, `sc:playlist:<id>` (playlists and albums), `sc:user:<id>` (artists), and `sc:likes:<userId>` for the linked profile's likes, shown as a playlist.

## Streams

`media.transcodings` lists the encodings of a track:

| `format.protocol` | `format.mime_type` | Melo |
| --- | --- | --- |
| `hls` | `audio/mp4; codecs="mp4a.40.2"` (presets `aac_160k`, `aac_96k`) | Used, best bitrate first |
| `progressive` | `audio/mpeg` | Used when there is no AAC |
| `hls` | `audio/ogg; codecs="opus"` | Not used |
| `ctr-encrypted-hls`, `cbc-encrypted-hls` | `audio/mp4` | Not used: protected |

`GET <transcoding.url>?client_id=&track_authorization=` returns `{"url": "<signed address>"}`. Signed addresses carry `expires=<seconds>` and stop working after it.

A track's `policy` matters: `SNIP` streams only a 30-second preview (Go+ content) and its transcodings are `snipped`; `BLOCK` streams nothing. Melo plays such tracks, and protected ones, from their YouTube Music match instead. So it does when every stream offered is refused or missing (`401`/`403` with a current client id, `404`, `410`), which is how regional limits show. A network or service failure is reported instead: it never moves a track to another source.

## From HLS segments to one progressive file

The AAC media playlist is plain fragmented MP4: an `#EXT-X-MAP` initialization section (`ftyp` + `moov` with `mvex`) and segments of about 10 s, each `styp` + `sidx` + `moof` + `mdat`, with no `#EXT-X-KEY`.

Melo's offline cache, downloads, audio index and preloading all expect one file per track, read by byte range. `SoundCloudDataSource` therefore presents each track as one progressive fragmented MP4:

```
init section | sidx (built by Melo) | segment 1 | segment 2 | … | segment n
```

- Segment sizes come from parallel `HEAD` requests; durations from `#EXTINF`.
- The `sidx` is version 1, timescale 1 000 000, one media reference per segment (size, duration, starts with a SAP). Its first offset is 0: segment 1 follows it.
- Each segment's own `sidx` is renamed `free` while it is read (only the 4 type bytes change). Otherwise the extractor would take the one-entry index of a segment for the index of the whole file. `styp` boxes between segments are skipped by MP4 extractors.
- A read at any offset maps to one segment and is served with a ranged `GET`; expired signed addresses are renewed and the read retried. Renewal asks for the same preset, whose segment sizes the layout holds. When SoundCloud no longer offers it, or the segment count changed, the layout is dropped and the track is laid out again on the next read.

The cache key is `audio:sc-<id>`, so finished files are found again offline like YouTube Music ones. The same key holds the YouTube Music match of a track that is played elsewhere. When the length of what is served under it changes (another preset, or a switch between SoundCloud and the match), the cached bytes are removed before anything is read, so two streams are never mixed in one file.

**Checked offline:** a file built this way from a real `aac_160k` init section and two real segments decodes without error with FFmpeg 9.0, seeks into the second segment, and has signal after the seek. 

**Checked on an emulator (Android 14, x86_64, debug build without the native runtime):**

- a public track plays through Media3 (`AAC · 44.1 kHz · 160 kbps`, full duration reported);
- a seek to 3:32 keeps playing, and the cached span written after the seek starts exactly on a segment boundary;
- the cached bytes, pulled from `no_backup/recent-audio`, decode with FFmpeg and carry signal both at the start of the file and after the seek (mean volume around -10 dB), with the synthesized `sidx` and the neutralized `free` boxes in place;
- the next SoundCloud track starts on its own at the end of the current one, and the following tracks are preloaded;
- a downloaded album plays with Wi-Fi and mobile data turned off;
- a Go+ track, opened from its link, plays its full YouTube Music match instead of the 30-second preview.

Not checked: a physical ARM64 device, and long sessions where the signed segment addresses expire while a track is paused.
