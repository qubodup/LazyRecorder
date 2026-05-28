# Lazy Recorder

<img src="lazylogo.png" width="64"> Lazy audio recorder that timestamps and location-GPS-stamps the filename

<img width="270" height="600" alt="image" src="https://github.com/user-attachments/assets/b430cba9-f198-4177-b880-e327e63f5f0a" />
<img width="270" height="600" alt="image" src="https://github.com/user-attachments/assets/a01c194c-6b91-437d-92fa-b25f10f6119d" />
<img width="270" height="600" alt="image" src="https://github.com/user-attachments/assets/5b20358c-7b90-4257-a93b-62ffa0899d5a" />

Features:
- Records only in the best format available on my phone (16bit 48kHz stereo WAV as far as I know @ Motorola moto G34)
- Just a button + a little bit of visualization
- Tracks GPS location at end of the recording (after trying to 'wake it up' at the beginning of the recording)
- Fixed recording file path
- No file(name) manager
- WAV should be recoverable after crash without having to fix header unless that last fraction of a second was reaaaaally important.

Vibecoded more or less with:

```
write an android app that gets all the necessary permissions on launch.
it has a big fat record button that turns into a big fat stop button upon recording done. It has no file manager and is unaware of any files.
it records to /Internal shared storage/Recordings/LazyRecorder
It gets the geo location at recording start but doesn't do anything with it. on recording end it gets it again, this one it uses.

if you start recording without having given all necessary permissions, it wont start and ask for permissions instead.

it writes timecode and geodata in the filename
Rec_20260121_110301_GPS_13.9453_41.1349.wav

it only records in 48Khz 16bit wav stereo

after stopping the recording, once the saving of the file finishes, it shows a toast with the location if it succeeded and a warning toast if it failed.

make the background #333
make the button red while waiting to record and green while recording
buttons use a circle and a square instead of text
during recording let's have the background symbolize the sound wave by having I guess 16 bars of the last few samples from center to top and bottom like a simple soundwave in audacity or something, #666 color, behind the rec/stop button, which i guess should be 90% opaque.
the toast should mention path and full filename not gps
Use "Internal storage/Recordings/LazyRecorder/${finalFile.name}" for toast
We have to block recording until the last file has been confirmed written
clear soundwave visualization so it doesn't show the 'old soundwaves' when starting a new recording

split the wave into two, one at top and one at bottom (locations of mics) and draw both.
but at each moment the one that is louder should be twice as opaque. if both are exactly identical, make both twice as opaque.
make it so I can switch L/R channel to up/down easily, after I figure out which is which (val leftChannelIsTop = false)

let's update the header (with the file lengt to enable file recovery on crash) once every few. i guess once every .2 seconds or so is enough, for recovery upon crash.
also make it stop before it reaches max possible wav size
```
