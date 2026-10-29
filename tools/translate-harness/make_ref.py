#!/usr/bin/env python3
"""Builds opus-ref.json: reference token ids (Hugging Face `tokenizers`, the library tokenizer.json is written for) and
reference translations (onnxruntime in Python, same version as the app (1.28.0), same int8 encoder + merged decoder),
greedy (beams 1) and beam search (beams 4, the models' num_beams), step for step what OpusMtModel.translate does, for the
Kotlin harness to match.
Needs: pip install tokenizers onnxruntime==1.28.0 numpy; model dirs laid out like the app's (encoder.onnx, decoder.onnx,
tokenizer.json, config.json).  Usage: make_ref.py <models-root> [pair ...] > opus-ref.json
(with pairs: only those pairs' entries, e.g. to add a new pair's reference without every other model on hand)."""
import sys, json, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
ROOT = sys.argv[1]
ONLY = set(sys.argv[2:])
G = 1  # greedy
B = 4  # beam search
LENGTH_PENALTY = 0.5  # hypothesis score = summed log probability / length ** this (1.0 made en-zh loop on short sentences)
TEXTS = {
    "en-de": [(G, "I will meet Sarah at the station tomorrow morning."), (G, "Remember: buy 2 litres of milk, eggs & bread!"),
              (G, "The quick brown fox jumps over the lazy dog."), (G, "  Spaces   everywhere,\tand a tab. "), (G, "Café, naïve résumé — déjà vu?"),
              (G, "Call Dr. O'Neil at 9:30am (ask about the 'blue' file)."), (B, "I will meet Sarah at the station tomorrow morning.")],
    "de-en": [(G, "Ich treffe Sarah morgen früh am Bahnhof."), (G, "Vergiss nicht, die Rechnung bis Freitag zu bezahlen.")],
    "en-hi": [(G, "Remember to buy milk and call mum tonight."), (G, "The meeting moved to Thursday at 3 pm."),
              (B, "Remember to buy milk and call mum tonight.")],
    "hi-en": [(G, "कल सुबह मुझे डॉक्टर के पास जाना है।")],
    "ja-en": [(G, "明日の朝、駅で会いましょう。"), (G, "牛乳と卵を買うのを忘れないでください。")],
    "en-es": [(G, "Don't forget the keys on the kitchen table."), (G, "Ideas for the weekend: hike, read, sleep in.")],
    # Greedy loops on the first (to the step limit, then cut to one copy) and drops half of the last; beam search doesn't.
    "en-zh": [(G, "I came home."), (B, "I came home."), (B, "I came home, and I was tired."), (B, "I was tired."), (B, "It rained."),
              (B, "When I got back home this evening I realised that I had forgotten my keys at the office again, so I had to wait outside for almost an hour until my neighbour came back with the spare set."),
              (G, "I came home I came home and then I went to sleep because I was really tired after the long drive from Sydney to Canberra and back again in one day."),
              (B, "I came home I came home and then I went to sleep because I was really tired after the long drive from Sydney to Canberra and back again in one day.")],
    # Malayalam: our own exports (akm9191/opus-mt-*-int8-onnx). The last ml-en one is code-mixed (English words in
    # Malayalam), which these models translate badly; kept so a change in that shows up.
    "en-ml": [(G, "How are you today?"), (G, "I came home late."), (B, "I came home late."),
              (B, "Remember to buy milk and call mum tonight."), (B, "The meeting moved to Thursday at 3 pm.")],
    "ml-en": [(G, "ഞാൻ വൈകിയാണ് വീട്ടിൽ വന്നത്."), (B, "ഞാൻ വൈകിയാണ് വീട്ടിൽ വന്നത്."), (B, "ഇന്ന് എങ്ങനെയുണ്ട്?"),
              (B, "നാളെ രാവിലെ ഞാൻ സാറയെ സ്റ്റേഷനിൽ കാണും."), (B, "പാൽ വാങ്ങാനും ഇന്ന് രാത്രി അമ്മയെ വിളിക്കാനും മറക്കരുത്."),
              (B, "ഞാൻ office-ൽ നിന്ന് late ആയി വന്നു.")],
}

def loop_cut(ids):
    """Decoding hit the step limit: if a span of 2..16 tokens repeats 3+ times in a row, keep only its first copy."""
    n = len(ids)
    for i in range(n):
        for k in range(2, 17):
            if i + 3 * k > n: break
            if ids[i:i+k] == ids[i+k:i+2*k] == ids[i+2*k:i+3*k]: return ids[:i+k]
    return ids

def translate(enc, dec, cfg, ids, nb):
    L, H = cfg["decoder_layers"], cfg["decoder_attention_heads"]; dh = cfg["d_model"] // H
    pad, eos, start = cfg["pad_token_id"], cfg["eos_token_id"], cfg["decoder_start_token_id"]
    names = [o.name for o in dec.get_outputs()]
    a = np.array([ids], np.int64); m = np.ones_like(a)
    h = enc.run(None, {"input_ids": a, "attention_mask": m})[0].copy()
    past = {f"past_key_values.{l}.{k}.{v}": np.zeros((1, H, 0, dh), np.float32) for l in range(L) for k in ("decoder", "encoder") for v in ("key", "value")}
    beams, cur, finished, mm, hh, hit_limit = [([], 0.0)], [start], [], m, h, True
    for step in range(min(cfg.get("max_length", 512), len(ids) * 3 + 10)):
        k = len(beams)
        if step == 1 and k > 1: mm = np.repeat(m, k, 0); hh = np.repeat(h, k, 0)
        feed = {"encoder_attention_mask": mm, "input_ids": np.array(cur, np.int64).reshape(k, 1), "encoder_hidden_states": hh,
                "use_cache_branch": np.array([step > 0])}; feed.update(past)
        r = dec.run(None, feed)
        lg = r[0][:, -1].astype(np.float64); lg[:, pad] = -np.inf
        mx = lg.max(1, keepdims=True); lp = lg - (mx + np.log(np.exp(lg - mx).sum(1, keepdims=True)))
        cand = []
        for b in range(k):
            row = lp[b]
            for t in sorted(np.argpartition(-row, 2 * nb)[:2 * nb].tolist(), key=lambda t: (-row[t], t)):
                cand.append((beams[b][1] + row[t], b, t))
        cand.sort(key=lambda c: (-c[0], c[1], c[2]))
        nxt = []
        for rank, (sc, b, t) in enumerate(cand):
            if t == eos:
                if rank < nb: finished.append((beams[b][0], sc / (len(beams[b][0]) + 1) ** LENGTH_PENALTY))
            else: nxt.append((beams[b][0] + [t], sc, b))
            if len(nxt) == nb: break
        order = [b for _, _, b in nxt]
        for n, t in zip(names[1:], r[1:]):
            key = n.replace("present", "past_key_values")
            if ".decoder." in key: past[key] = t[order].copy()  # own the bytes: never alias a reused output buffer
            elif step == 0: past[key] = np.repeat(t, len(nxt), 0).copy()
        beams = [(toks, sc) for toks, sc, _ in nxt]; cur = [toks[-1] for toks, _ in beams]
        if len(finished) >= nb:
            if nb == 1 or min(s for _, s in finished) >= beams[0][1] / (len(beams[0][0]) + 1) ** LENGTH_PENALTY: hit_limit = False; break
    if hit_limit: finished += [(loop_cut(toks), sc / (len(toks) + 1) ** LENGTH_PENALTY) for toks, sc in beams]
    return max(finished, key=lambda f: f[1])[0]

out = []
for pair, texts in TEXTS.items():
    if ONLY and pair not in ONLY: continue
    d = f"{ROOT}/{pair}"
    tj = json.load(open(f"{d}/tokenizer.json")); tj["normalizer"] = None  # null charsmap: identity
    tok = Tokenizer.from_str(json.dumps(tj))
    cfg = json.load(open(f"{d}/config.json"))
    # One thread: multi-threaded float sums can flip near-tie tokens run to run; one thread is exactly repeatable.
    so = ort.SessionOptions(); so.intra_op_num_threads = 1
    enc = ort.InferenceSession(f"{d}/encoder.onnx", so); dec = ort.InferenceSession(f"{d}/decoder.onnx", so)
    for nb, text in texts:
        ids = tok.encode(text).ids
        out.append({"pair": pair, "text": text, "beams": nb, "ids": ids, "translation": tok.decode(translate(enc, dec, cfg, ids, nb)).strip()})  # the app trims too
json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
