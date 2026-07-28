// Career: chapters of events, each with an objective, a reward and an unlock.

export const CHAPTERS = [
  {
    id: 'ch1',
    name: 'Midnight introductions',
    nameJp: '深夜の顔合わせ',
    blurb: 'Nobody knows the car yet. Learn the block, learn the throttle.',
    events: [
      {
        id: 'ch1_e1', track: 'shibuya', name: 'First lap out',
        brief: 'Warm the tires and hold a slide through the first two corners.',
        objective: { type: 'score', target: 8000 },
        duration: 150, hour: 22.5, weather: 'clear',
        reward: 3500, rep: 220,
      },
      {
        id: 'ch1_e2', track: 'shibuya', name: 'Clip the crossing',
        brief: 'Hit four clipping zones in one run without straightening up.',
        objective: { type: 'zones', target: 4 },
        duration: 180, hour: 23.5, weather: 'clear',
        reward: 5200, rep: 320,
      },
      {
        id: 'ch1_e3', track: 'shibuya', name: 'Two-minute score',
        brief: 'Bank 45,000 points before the clock runs out.',
        objective: { type: 'score', target: 45000 },
        duration: 120, hour: 1.0, weather: 'cloudy',
        reward: 9000, rep: 520, unlocks: 'docks',
      },
    ],
  },
  {
    id: 'ch2',
    name: 'Down at the docks',
    nameJp: '埠頭の夜',
    blurb: 'Wide, wet concrete and floodlights. Big angle territory.',
    require: { track: 'docks' },
    events: [
      {
        id: 'ch2_e1', track: 'docks', name: 'Wet floor',
        brief: 'Rain, standing water, and a surface that will not forgive a stab of throttle.',
        objective: { type: 'score', target: 30000 },
        duration: 180, hour: 21.0, weather: 'wet',
        reward: 7800, rep: 480,
      },
      {
        id: 'ch2_e2', track: 'docks', name: 'Container run',
        brief: 'One continuous drift worth 20,000 points. No banking early.',
        objective: { type: 'single', target: 20000 },
        duration: 210, hour: 2.0, weather: 'clear',
        reward: 11500, rep: 720,
      },
      {
        id: 'ch2_e3', track: 'docks', name: 'Sea fog',
        brief: 'Visibility is gone. Drive by the floodlights and memory.',
        objective: { type: 'score', target: 90000 },
        duration: 210, hour: 4.5, weather: 'fog',
        reward: 16000, rep: 980, unlocks: 'haruka',
      },
    ],
  },
  {
    id: 'ch3',
    name: 'Up the pass',
    nameJp: '峠へ',
    blurb: 'Cold tarmac, a guardrail on one side and nothing on the other.',
    require: { track: 'haruka' },
    events: [
      {
        id: 'ch3_e1', track: 'haruka', name: 'First climb',
        brief: 'Eight metres of road. Do not use all of it.',
        objective: { type: 'score', target: 40000 },
        duration: 210, hour: 1.5, weather: 'clear',
        reward: 14000, rep: 900,
      },
      {
        id: 'ch3_e2', track: 'haruka', name: 'Downhill in the rain',
        brief: 'Gravity is helping and it is not on your side.',
        objective: { type: 'score', target: 75000 },
        duration: 240, hour: 3.0, weather: 'wet',
        reward: 21000, rep: 1300,
      },
      {
        id: 'ch3_e3', track: 'haruka', name: 'Sunrise run',
        brief: 'The last run before the road wakes up. Make it count.',
        objective: { type: 'score', target: 160000 },
        duration: 300, hour: 4.2, weather: 'clear', timeScale: 0.22,
        reward: 42000, rep: 2600,
      },
    ],
  },
];

export const ALL_EVENTS = CHAPTERS.flatMap((c) => c.events.map((e) => ({ ...e, chapter: c.id })));

export function getEvent(id) {
  return ALL_EVENTS.find((e) => e.id === id) || null;
}

export function isChapterUnlocked(chapter, profile) {
  if (!chapter.require) return true;
  if (chapter.require.track) return profile.data.unlockedTracks.includes(chapter.require.track);
  return true;
}

export function isEventUnlocked(event, profile) {
  const chapter = CHAPTERS.find((c) => c.id === event.chapter);
  if (chapter && !isChapterUnlocked(chapter, profile)) return false;
  const list = chapter ? chapter.events : [];
  const idx = list.findIndex((e) => e.id === event.id);
  if (idx <= 0) return true;
  return !!profile.data.completed[list[idx - 1].id];
}

/** How close the run came to the objective, as 0–3 stars. */
export function evaluate(event, run) {
  const o = event.objective;
  let value = 0;
  switch (o.type) {
    case 'zones': value = run.zonesCleared; break;
    case 'single': value = run.bestSingle; break;
    case 'score': default: value = run.score; break;
  }
  const ratio = value / o.target;
  let stars = 0;
  if (ratio >= 1) stars = 1;
  if (ratio >= 1.35) stars = 2;
  if (ratio >= 1.9) stars = 3;
  return {
    stars,
    value,
    target: o.target,
    passed: stars > 0,
    reward: Math.round(event.reward * (1 + (stars - 1) * 0.35) * (stars > 0 ? 1 : 0)),
    rep: Math.round(event.rep * (stars > 0 ? 1 + (stars - 1) * 0.3 : 0.12)),
  };
}

export function objectiveText(event) {
  const o = event.objective;
  switch (o.type) {
    case 'zones': return `Clear ${o.target} clipping zones`;
    case 'single': return `One drift worth ${o.target.toLocaleString('en-US')}`;
    default: return `Score ${o.target.toLocaleString('en-US')} points`;
  }
}

export function careerProgress(profile) {
  const done = ALL_EVENTS.filter((e) => profile.data.completed[e.id]).length;
  const stars = ALL_EVENTS.reduce((sum, e) => sum + (profile.data.completed[e.id]?.stars || 0), 0);
  return { done, total: ALL_EVENTS.length, stars, maxStars: ALL_EVENTS.length * 3 };
}
