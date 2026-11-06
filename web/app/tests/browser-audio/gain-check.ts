import { scheduleTransition } from '../../src/playback/automix/engine/transition-scheduler';
import { Timeline } from '../../src/playback/automix/engine/timeline';
import type { Deck } from '../../src/playback/automix/engine/deck';
import type { TrackAnalysis } from '../../src/playback/automix/analysis/types';

const SR=48000;
const output=document.querySelector('#result')!;
function analysis(fileKey: string, grid: boolean): TrackAnalysis {
  return {fileKey,path:'',tags:{title:fileKey,artist:'Fixture'},durationSec:60,
    bpm:grid?120:null,beatIntervalSec:grid?0.5:null,firstDownbeatSec:grid?0.5:null,
    gridConfidence:grid?0.8:0,camelot:null,keyName:null,keyConfidence:0,energy:0.5,energyCurve:[],
    introEndSec:0,outroStartSec:60,analysisVersion:9};
}
async function check(style: string) {
  const ctx=new OfflineAudioContext(1,15*SR,SR);
  const outSource=new ConstantSourceNode(ctx,{offset:0.4});
  const inSource=new ConstantSourceNode(ctx,{offset:0.3});
  const outGain=new GainNode(ctx,{gain:1});
  const inGain=new GainNode(ctx,{gain:1});
  outSource.connect(outGain).connect(ctx.destination);
  inSource.connect(inGain).connect(ctx.destination);
  const deck=(source:ConstantSourceNode,gain:GainNode)=>{
    const timeline=new Timeline(); timeline.add({output:0,input:10,rate:1,active:true});
    return {ctx,gain,timeline,scheduleAhead:0.17,expectedTrackTime:10,
      eq:{setGainAt(){},rampGain(){},reset(){}},
      sweep:{highpass:new BiquadFilterNode(ctx),lowpass:new BiquadFilterNode(ctx)},
      playAt({ctxTime}:{ctxTime:number}){source.start(Math.max(0,ctxTime-0.12));},
      pauseAt(time:number){source.stop(time);},setRate(){},setVolume(){}} as unknown as Deck;
  };
  const from=deck(outSource,outGain),to=deck(inSource,inGain);
  outSource.start();
  // Suppress the historical wall-clock cleanup during setup. Current audio-
  // clock cleanup retires when this faster-than-real-time context closes.
  const nativeTimeout=window.setTimeout;
  const timers:number[]=[];
  window.setTimeout=((...args:Parameters<typeof window.setTimeout>)=>{
    const timer=nativeTimeout(...args);timers.push(timer);return timer;
  }) as typeof window.setTimeout;
  let plan;
  try {plan=scheduleTransition({from,to,fromAnalysis:analysis('out',style!=='crossfade'),
    toAnalysis:analysis('in',style==='blend'),bars:4,forceNow:true});}
  finally {window.setTimeout=nativeTimeout;timers.forEach(clearTimeout);}
  const rendered=await ctx.startRendering();
  const samples=rendered.getChannelData(0);
  const beforeStart=Math.ceil((plan.t0-0.1)*SR);
  const beforeEnd=Math.floor((plan.t0-0.03)*SR);
  let beforePeak=0,duringPeak=0,wrongEarlyFrames=0;
  for(let i=beforeStart;i<beforeEnd;i++){
    beforePeak=Math.max(beforePeak,Math.abs(samples[i]!));
    if(Math.abs(samples[i]!-0.4)>0.00001)wrongEarlyFrames++;
  }
  for(let i=Math.ceil(plan.t0*SR);i<Math.min(samples.length,Math.floor((plan.t0+plan.durationSec)*SR));i++)
    duringPeak=Math.max(duringPeak,Math.abs(samples[i]!));
  return {requestedStyle:style,actualStyle:plan.style,t0:plan.t0,duration:plan.durationSec,
    beforePeak,duringPeak,wrongEarlyFrames,expectedBeforePeak:0.4,
    passed:plan.style===style && wrongEarlyFrames===0 && duringPeak<=0.5001};
}
(document.querySelector('#run') as HTMLButtonElement).onclick=async event=>{
  (event.currentTarget as HTMLButtonElement).disabled=true;
  const report:any={scope:'Actual production scheduleTransition with real OfflineAudioContext gains; media-element start lead modeled at the real Deck default 120 ms. This isolates gain scheduling, not all player/decoder behavior.',cases:[],errors:[]};
  try {for(const style of ['crossfade','blend','filter'])report.cases.push(await check(style));}
  catch(error){report.errors.push(String(error));}
  report.passed=report.cases.length===3 && report.cases.every((x:any)=>x.passed) && !report.errors.length;
  output.textContent=JSON.stringify(report,null,2);
  await fetch('/_result',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(report)});
};
