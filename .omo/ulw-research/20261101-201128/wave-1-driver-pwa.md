# wave-1 driver PWA digest
- Tour loop: assigned→preview→start→per-stop navigate/arrive/scan/complete(delivered/failed/re-attempt/partial)→end route; offline open-once-cache.
- PoD: timestamp + sig/photo/scan + GPS; 7-step Upper flow; ≤2 photos; signature-skip only with photo→contactless flag; OTP alternative.
- Duty: OFF/SB/D/ON + YM/PC; 5mph auto-D; 5min+1min auto-ON; tracked drive immutable.
- SOS: one-press GPS+timestamp, silent/duress; Uber shield→RapidSOS; lone-worker server timer.
- Offline: SW shell + IndexedDB queue, opId idempotent, server-wins conflicts, true timestamps.
