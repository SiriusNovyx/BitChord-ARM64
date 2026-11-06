`red.mp4` is a synthetic six-frame, 64x64 MPEG-4 video with a red background.
It contains no audio or third-party media. It was generated with:

```text
ffmpeg -f lavfi -i color=c=red:s=64x64:r=25 -frames:v 6 -c:v mpeg4 -pix_fmt yuv420p red.mp4
```

The decoder regression checks native video decoding, BGRA channel order and
looping across EOF on both Windows architectures.

`timed.mp4` is the Canvas seek/resume fixture retained from the v1.9
reconciliation. Tests observe 125 ms frame spacing and restart its captured
750 ms frame in a new native decoder, comparing the actual BGRA pixels.
Its SHA-256 is
`774bdc3c34155ee949c74688a46dd87aa37a29c187fa686885ad275fb41285a5`.
The exact generating command was not retained by the preceding session;
the checked-in bytes and the observed native timing/pixel tests are the
verification reference.
