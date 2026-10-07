# Wake-word model goes here

The Vosk speech model (~40 MB) is not committed to this repository.

1. Download a small English model from https://alphacephei.com/vosk/models
2. Unzip it and rename the folder to `model-en-us`
3. Create a plain text file named `uuid` inside it, containing any text (e.g. `model-1`)
4. Place the folder here, so the path is:

```
app/src/main/assets/model-en-us/
├── am/
├── conf/
├── graph/
├── ivector/
└── uuid
```

Without this folder the app still builds and runs; only the "Hey Brain" wake word is unavailable.
