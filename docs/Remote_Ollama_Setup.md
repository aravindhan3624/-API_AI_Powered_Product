# Using Ollama on another PC

Studio never needs a language model. The optional AI features (test ideas, plain-English explanations) can use one that runs on **another machine** on your network. This PC never runs a model.

## On the other PC (the model host)
1. Install Ollama from https://ollama.com (Windows installer).
2. Make it listen on the network: set the user environment variable `OLLAMA_HOST` to `0.0.0.0:11434`, then restart Ollama.
3. Download a model once (needs internet on that PC): `ollama pull qwen2.5:3b` (small) or `qwen2.5:7b` / `qwen2.5:14b` if that PC has the RAM or GPU.
4. Allow inbound TCP port 11434 in Windows Firewall, only for your office network.
5. Find its address: run `ipconfig` and note the IPv4 address, for example 192.168.1.50.

Security note: Ollama has no login. Only expose it on a trusted network or VPN, never to the internet.

## In Studio (this PC)
Open **AI engine (optional)**, tick "Use a language model", set **Model address** to `http://192.168.1.50:11434`, keep Model on `auto`, and Save. A green "Connected" line with the active model means it works.

Or start Studio with options: `-Dai.url=http://192.168.1.50:11434 -Dai.model=auto`. The settings file is `%USERPROFILE%\.api-quality-studio\ai.json`; it can also hold `connectTimeoutMs` (0 = automatic: 800 ms for this PC, 4000 ms for another machine).

## What changes when the address is another machine
- `auto` picks the best installed model **there**; it is no longer limited by this PC's RAM.
- Gentle mode no longer limits CPU threads based on this PC's cores (it still keeps requests small).
- The connection check waits longer so a slow network does not show "not reachable".
- If the host is off or unreachable, Studio keeps working with its built-in rule engine.
