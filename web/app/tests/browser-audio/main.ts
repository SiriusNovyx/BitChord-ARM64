import { createAutomixGraph, analyzer } from '../../src/playback/automix';
import type { TrackAnalysis } from '../../src/playback/automix/analysis/types';

const output = document.querySelector('#result')!;
const button = document.querySelector('#run') as HTMLButtonElement;
const pause = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
const ensure = (condition: unknown, message: string) => { if (!condition) throw new Error(message); };
async function until(test: () => boolean, timeout = 30_000) {
  const deadline = performance.now() + timeout;
  while (!test()) {
    if (performance.now() > deadline) throw new Error('Condition timed out');
    await pause(50);
  }
}

function wave(frequency: number, seconds = 40, sr = 44100): ArrayBuffer {
  const frames = seconds * sr;
  const data = new ArrayBuffer(44 + frames * 4);
  const view = new DataView(data);
  const text = (offset: number, value: string) => [...value].forEach((c, i) => view.setUint8(offset + i, c.charCodeAt(0)));
  text(0, 'RIFF'); view.setUint32(4, data.byteLength - 8, true); text(8, 'WAVE'); text(12, 'fmt ');
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 2, true);
  view.setUint32(24, sr, true); view.setUint32(28, sr * 4, true);
  view.setUint16(32, 4, true); view.setUint16(34, 16, true); text(36, 'data'); view.setUint32(40, frames * 4, true);
  for (let i = 0; i < frames; i++) {
    const t = i / sr;
    const envelope = Math.min(1, t * 100, (seconds - t) * 100);
    const pulse = 0.8 + 0.2 * Math.exp(-12 * (t % 0.5));
    const sample = 0.18 * envelope * pulse * (Math.sin(2 * Math.PI * frequency * t) + 0.15 * Math.sin(2 * Math.PI * frequency * 3 * t));
    view.setInt16(44 + i * 4, Math.round(sample * 32767), true);
    view.setInt16(46 + i * 4, Math.round(sample * 0.8 * 32767), true);
  }
  return data;
}

function analysis(fileKey: string): TrackAnalysis {
  return { fileKey, path: '', tags: {title: fileKey, artist: 'Generated fixture'}, durationSec: 40,
    bpm: 120, beatIntervalSec: 0.5, firstDownbeatSec: 0.5, gridConfidence: 0.8,
    camelot: null, keyName: null, keyConfidence: 0, energy: 0.5, energyCurve: [],
    introEndSec: 0, outroStartSec: 40, analysisVersion: 9 };
}

const meterSource = `class Capture extends AudioWorkletProcessor {
  frames=0; active=0; peak=0; over=0; invalid=0; sum=0;
  process(inputs,outputs) {
    const input=inputs[0]||[];
    const count=input[0]?.length||128;
    for(let i=0;i<count;i++) {
      let active=false;
      for(let ch=0;ch<input.length;ch++) {
        const x=input[ch][i];
        if(!Number.isFinite(x)) this.invalid++;
        else {this.peak=Math.max(this.peak,Math.abs(x));this.sum+=x*x; if(Math.abs(x)>1)this.over++; if(Math.abs(x)>1e-6)active=true;}
      }
      if(active)this.active++;
    }
    for(let ch=0;ch<outputs[0].length;ch++) if(input[ch])outputs[0][ch].set(input[ch]);
    this.frames+=count;
    if(this.frames>=4096){this.port.postMessage({frames:this.frames,active:this.active,peak:this.peak,over:this.over,invalid:this.invalid,rms:Math.sqrt(this.sum/(this.frames*Math.max(1,input.length))),time:currentTime});this.frames=this.active=this.peak=this.over=this.invalid=this.sum=0;}
    return true;
  }
} registerProcessor('fixture-capture',Capture);`;

button.onclick = async () => {
  button.disabled = true;
  const report: any = {scope: 'Actual createAutomixGraph, HTMLAudioElement decks and Signalsmith WASM worklets, generated PCM/known analysis only; no provider or audible output',
    userAgent: navigator.userAgent, scenarios: [], errors: []};
  const audios = [new Audio(), new Audio()];
  const urls: string[] = [];
  let graph: ReturnType<typeof createAutomixGraph> | undefined;
  let knownAnalysis = false;
  let front = 0;
  let current = 'out';
  let next: string | null = 'in';
  let handed = 0;
  let finished = 0;
  let held = false;
  let phase = 'setup';
  let loops: any[] = [];
  const meter: any[] = [];
  const states: any[] = [];
  const playing = () => ({videoId: current, title: current, artist: 'Generated fixture'});
  const bytes: Record<string, ArrayBuffer> = {out: wave(110), in: wave(165)};
  const original = {get: analyzer.get, request: analyzer.request, audioBytes: analyzer.audioBytes};
  try {
    report.inputSha256 = Object.fromEntries(await Promise.all(Object.entries(bytes).map(async ([key,data]) =>
      [key,Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data))).map(x=>x.toString(16).padStart(2,'0')).join('')])));
    analyzer.get = key => knownAnalysis ? analysis(key) : null;
    analyzer.request = () => {};
    analyzer.audioBytes = async key => bytes[key]!.slice(0);
    graph = createAutomixGraph(audios, decks => ({
      front: () => decks[front]!, back: () => decks[1-front]!, current: playing,
      next: () => next ? {videoId: next, title: next, artist: 'Generated fixture'} : null,
      loadBack: async id => { decks[1-front]!.loaded = {fileKey: id, title: id, artist: 'Generated fixture'}; return true; },
      handoff: () => { handed++; states.push({event: 'handoff', phase, time: graph!.ctx.currentTime,
        mixPlayhead: graph!.dj.mixPlayhead('in'), elementPosition: audios[1]!.currentTime}); front = 1; current = 'in'; next = null; },
      finished: () => { finished++; states.push({event: 'finished', phase, time: graph!.ctx.currentTime}); },
      sungSpans: async () => [], hold: on => {held = on;},
    }));
    await graph.ctx.resume();
    // Capture the real graph upstream of a silent final node. This changes only
    // the fixture's graph and never the system volume or another player.
    graph.master.disconnect();
    const workletUrl = URL.createObjectURL(new Blob([meterSource], {type: 'text/javascript'})); urls.push(workletUrl);
    await graph.ctx.audioWorklet.addModule(workletUrl);
    const capture = new AudioWorkletNode(graph.ctx, 'fixture-capture', {outputChannelCount: [2]});
    capture.port.onmessage = event => meter.push({phase, ...event.data});
    const mute = new GainNode(graph.ctx, {gain: 0});
    graph.master.connect(capture).connect(mute).connect(graph.ctx.destination);
    const internal = graph.dj as any;
    const factory = internal.host.loopDecks;
    internal.host.loopDecks = async () => (loops = await factory());
    for (let i = 0; i < audios.length; i++) {
      const id = i ? 'in' : 'out';
      const url = URL.createObjectURL(new Blob([bytes[id]!], {type: 'audio/wav'})); urls.push(url);
      audios[i]!.src = url; audios[i]!.preload = 'auto';
      await until(() => audios[i]!.readyState >= HTMLMediaElement.HAVE_METADATA);
      graph.decks[i]!.loaded = {fileKey: id, title: id, artist: 'Generated fixture'};
    }
    const summarize = (name: string, start: number, extra: any = {}) => {
      const blocks = meter.slice(start).filter(x => x.phase === phase);
      const summary = {name, blocks: blocks.length, frames: blocks.reduce((s,x)=>s+x.frames,0),
        activeFrames: blocks.reduce((s,x)=>s+x.active,0), peak: Math.max(0,...blocks.map(x=>x.peak)),
        peakBlockEndSec: blocks.reduce((best,x)=>!best || x.peak>best.peak?x:best,null)?.time,
        overrangeSamples: blocks.reduce((s,x)=>s+x.over,0), nonfiniteSamples: blocks.reduce((s,x)=>s+x.invalid,0), ...extra};
      ensure(summary.activeFrames > graph!.ctx.sampleRate * 0.5, name + ': no real decoded PCM');
      ensure(summary.overrangeSamples === 0 && summary.nonfiniteSamples === 0, name + ': invalid/overrange PCM');
      report.scenarios.push(summary);
    };

    phase = 'normal'; output.textContent = 'Running actual HTMLAudioElement / Web Audio decode';
    let mark = meter.length;
    await audios[0]!.play();
    await pause(1200);
    summarize('normal generated stereo playback', mark, {sampleRate: graph.ctx.sampleRate, elementPosition: audios[0]!.currentTime});
    audios[0]!.pause(); graph.decks.forEach(deck => deck.cancel());

    phase = 'fallback'; output.textContent = 'Running fallback, pause/cancel, restart and actual handoff';
    audios[0]!.currentTime = 27.5;
    await until(() => !audios[0]!.seeking);
    await audios[0]!.play(); graph.decks[0]!.anchor();
    graph.dj.start();
    await until(() => graph!.dj.snapshot.state === 'scheduled');
    ensure(graph.dj.snapshot.plan?.fallback, 'Fallback not selected without analysis');
    await pause(1700);
    graph.dj.cancel(); audios.forEach(audio => audio.pause());
    await pause(200);
    const paused = audios.map(audio => audio.currentTime);
    await pause(400);
    ensure(audios.every((audio,i)=>Math.abs(audio.currentTime-paused[i]!)<0.02), 'Paused elements moved');
    ensure(handed === 0, 'Cancelled fallback promoted a stale track');
    graph.decks.forEach(deck => deck.anchor());
    await audios[0]!.play();
    mark = meter.length;
    await until(() => graph!.dj.snapshot.state === 'scheduled');
    const fallbackPlan = {...graph.dj.snapshot.plan};
    await pause(250);
    await graph.ctx.suspend();
    const suspension = {audioClock: graph.ctx.currentTime, elements: audios.map(audio=>audio.currentTime), handoffs: handed};
    await pause(350);
    const suspendedAfter = {audioClock: graph.ctx.currentTime, elements: audios.map(audio=>audio.currentTime), handoffs: handed};
    ensure(suspendedAfter.audioClock === suspension.audioClock && suspendedAfter.handoffs === suspension.handoffs,
      'Suspended context advanced ownership');
    await graph.ctx.resume();
    await until(() => finished === 1, 20_000);
    ensure(handed === 1, 'Fallback handoff count');
    const fallbackEvent = states.find(event=>event.phase==='fallback' && event.event==='handoff');
    const fallbackDelay = fallbackEvent.time - (fallbackPlan.t0! + fallbackPlan.durationSec!/2);
    ensure(fallbackDelay >= 0 && fallbackDelay < 0.2, 'Fallback metadata missed its audio-clock boundary');
    summarize('real fallback with pause/cancel and resumed handoff', mark, {plan: fallbackPlan, handed, finished,
      handoffDelaySec: fallbackDelay, pausedPositions: paused, contextSuspension: {before: suspension, after: suspendedAfter}});

    graph.dj.stop(); audios.forEach(audio => audio.pause()); graph.decks.forEach(deck => deck.cancel());
    front=0; current='out'; next='in'; handed=finished=0;
    audios[0]!.currentTime=0; audios[1]!.currentTime=0;
    await until(() => audios.every(audio=>!audio.seeking));
    knownAnalysis=true;
    graph.dj.transitionBars=4;
    phase='loop'; output.textContent='Preparing actual decoded buffers and Signalsmith WASM loop decks';
    await audios[0]!.play(); graph.decks[0]!.anchor(); graph.dj.start();
    await until(() => graph!.dj.snapshot.loop !== null, 30_000);
    const loopPlan = structuredClone(graph.dj.snapshot.loop!);
    ensure(loops.length === 2 && loops[0].loadedKey === 'out' && loops[1].loadedKey === 'in', 'Actual worklets not loaded');
    audios[0]!.currentTime = loopPlan.out.loop[0] - 3;
    await until(() => !audios[0]!.seeking); graph.decks[0]!.anchor();
    await until(() => graph!.dj.snapshot.plan?.style === 'loop', 5000);
    const scheduled = {...graph.dj.snapshot.plan};
    const loopHandoffAt = internal.loopTimes.handoffAt;
    ensure(held, 'Loop graph was not held by the real scheduler');
    mark=meter.length;
    output.textContent='Rendering the real loop mix, tempo clock and worklet-to-element promotion';
    await until(() => finished === 1, 35_000);
    ensure(handed === 1 && !held, 'Loop promotion or hold cleanup failed');
    ensure(audios[1]!.currentTime > loopPlan.in.loop[1], 'Incoming element never took over beyond the loop');
    const loopEvent = states.find(event=>event.phase==='loop' && event.event==='handoff');
    const loopDelay = loopEvent.time - loopHandoffAt;
    ensure(loopDelay >= 0 && loopDelay < 0.2, 'Loop metadata missed its actual ramp handoff time');
    summarize('actual Signalsmith loop transition and promotion', mark, {plan: loopPlan, scheduled, handed, finished,
      handoffAt: loopHandoffAt, handoffDelaySec: loopDelay,
      actualWorkletLatency: loops.map(loop=>loop.latencySec), loadedRegions: loops.map(loop=>({key:loop.loadedKey,start:loop.regionStart,end:loop.regionEnd})),
      incomingPosition: audios[1]!.currentTime, reportedPosition: graph.decks[1]!.reportedTrackTime});
    report.states=states;
    report.sampleRate=graph.ctx.sampleRate;
  } catch(error) {report.errors.push(String(error));}
  finally {
    if(graph) {
      graph.dj.stop(); audios.forEach(audio=>audio.pause()); graph.decks.forEach(deck=>deck.cancel());
      loops.forEach(loop=>loop.stop()); await graph.ctx.close();
      report.contextState=graph.ctx.state;
    }
    analyzer.get=original.get; analyzer.request=original.request; analyzer.audioBytes=original.audioBytes;
    urls.forEach(url=>URL.revokeObjectURL(url));
  }
  report.passed=report.scenarios.length===3 && report.errors.length===0 && report.contextState==='closed';
  output.textContent=JSON.stringify(report,null,2);
  await fetch('/_result',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(report)});
};
