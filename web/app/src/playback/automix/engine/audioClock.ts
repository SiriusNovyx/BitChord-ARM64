/** One cancellable wake steered by audio time, with no polling during suspension. */
export function atAudioTime(ctx: AudioContext, at: number, action: () => void, closed: () => void = () => {}): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;
  let last = ctx.currentTime;
  const cancel = () => {
    stopped = true;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    ctx.removeEventListener('statechange', changed);
  };
  const wake = () => {
    timer = null;
    if (stopped) return;
    if (ctx.state === 'closed') { cancel(); closed(); return; }
    if (ctx.state !== 'running') return;
    const now = ctx.currentTime;
    if (now >= at) { cancel(); action(); return; }
    // A stalled driver must not turn a nearly-due boundary into a busy loop.
    const minimumMs = now <= last ? 1000 : 10;
    last = now;
    timer = setTimeout(wake, Math.max(minimumMs, Math.ceil((at - now) * 1000)));
  };
  const changed = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    if (stopped) return;
    if (ctx.state === 'closed') { cancel(); closed(); return; }
    if (ctx.state !== 'running') return;
    last = ctx.currentTime;
    timer = setTimeout(wake, Math.max(0, Math.ceil((at - last) * 1000)));
  };
  ctx.addEventListener('statechange', changed);
  // Settle asynchronously even when the caller receives an already-closed clock.
  if (ctx.state === 'closed') timer = setTimeout(changed, 0);
  else changed();
  return cancel;
}
