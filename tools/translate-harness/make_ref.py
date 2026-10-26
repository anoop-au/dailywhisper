#!/usr/bin/env python3
"""Builds opus-ref.json: reference token ids (Hugging Face `tokenizers`, the library tokenizer.json is written for) and
reference greedy translations (onnxruntime in Python, same version as the app (1.23.2), same int8 encoder + merged decoder) for the Kotlin harness to match.
Needs: pip install tokenizers onnxruntime==1.23.2 numpy; model dirs laid out like the app's (encoder.onnx, decoder.onnx,
tokenizer.json, config.json).  Usage: make_ref.py <models-root> > opus-ref.json"""
import sys, json, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
ROOT = sys.argv[1]
TEXTS = {
    "en-de": ["I will meet Sarah at the station tomorrow morning.", "Remember: buy 2 litres of milk, eggs & bread!",
              "The quick brown fox jumps over the lazy dog.", "  Spaces   everywhere,\tand a tab. ", "Café, naïve résumé — déjà vu?",
              "Call Dr. O'Neil at 9:30am (ask about the 'blue' file)."],
    "de-en": ["Ich treffe Sarah morgen früh am Bahnhof.", "Vergiss nicht, die Rechnung bis Freitag zu bezahlen."],
    "en-hi": ["Remember to buy milk and call mum tonight.", "The meeting moved to Thursday at 3 pm."],
    "hi-en": ["कल सुबह मुझे डॉक्टर के पास जाना है।"],
    "ja-en": ["明日の朝、駅で会いましょう。", "牛乳と卵を買うのを忘れないでください。"],
    "en-es": ["Don't forget the keys on the kitchen table.", "Ideas for the weekend: hike, read, sleep in."],
}
out = []
for pair, texts in TEXTS.items():
    d = f"{ROOT}/{pair}"
    tj = json.load(open(f"{d}/tokenizer.json")); tj["normalizer"] = None  # null charsmap: identity
    tok = Tokenizer.from_str(json.dumps(tj))
    cfg = json.load(open(f"{d}/config.json"))
    # One thread: multi-threaded float sums can flip near-tie tokens run to run; one thread is exactly repeatable.
    so = ort.SessionOptions(); so.intra_op_num_threads = 1
    enc = ort.InferenceSession(f"{d}/encoder.onnx", so); dec = ort.InferenceSession(f"{d}/decoder.onnx", so)
    L, H = cfg["decoder_layers"], cfg["decoder_attention_heads"]; dh = cfg["d_model"] // H
    for text in texts:
        ids = tok.encode(text).ids
        a = np.array([ids], np.int64); m = np.ones_like(a)
        h = enc.run(None, {"input_ids": a, "attention_mask": m})[0].copy()
        past = {f"past_key_values.{l}.{k}.{v}": np.zeros((1, H, 0, dh), np.float32) for l in range(L) for k in ("decoder", "encoder") for v in ("key", "value")}
        names = [o.name for o in dec.get_outputs()]
        res_ids, cur = [], cfg["decoder_start_token_id"]
        for step in range(min(512, len(ids) * 3 + 10)):
            feed = {"encoder_attention_mask": m, "input_ids": np.array([[cur]], np.int64), "encoder_hidden_states": h,
                    "use_cache_branch": np.array([step > 0])}; feed.update(past)
            r = dec.run(None, feed)
            lg = r[0][0, -1].copy(); lg[cfg["pad_token_id"]] = -np.inf; cur = int(lg.argmax())
            for n, t in zip(names[1:], r[1:]):
                k = n.replace("present", "past_key_values")
                if step == 0 or ".decoder." in k: past[k] = t.copy()  # own the bytes: never alias a reused output buffer
            if cur == cfg["eos_token_id"]: break
            res_ids.append(cur)
        out.append({"pair": pair, "text": text, "ids": ids, "translation": tok.decode(res_ids)})
json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
