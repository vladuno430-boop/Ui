// The three venues. Control points are metres in world space; the spline
// smooths them into the driving line.

export const TRACKS = {
  shibuya: {
    id: 'shibuya',
    name: 'Shibuya Loop',
    nameJp: '渋谷ループ',
    theme: 'city',
    blurb: 'Neon-soaked city block circuit. Wide, fast, and lined with things you '
      + 'do not want to touch.',
    difficulty: 1,
    seed: 1187,
    width: [13.5, 13.5, 12, 12.5, 14, 13, 12, 13.5],
    shoulder: 2.2,
    wallDistance: 1.6,
    banking: 0.25,
    shoulderSurface: 'concrete',
    offTrackSurface: 'dirt',
    roadColor: [0.075, 0.077, 0.086],
    shoulderColor: [0.11, 0.11, 0.125],
    defaultTime: 22.5,
    defaultWeather: 'clear',
    points: [
      [0, 0, 0], [4, 0, 62], [0, 0, 118], [-18, 0, 168], [-62, 0, 196],
      [-124, 0, 205], [-178, 0, 186], [-208, 0, 142], [-206, 0, 92],
      [-224, 0, 44], [-262, 0, 6], [-282, 0, -48], [-262, 0, -104],
      [-208, 0, -136], [-148, 0, -136], [-96, 0, -152], [-46, 0, -168],
      [-6, 0, -140], [8, 0, -92], [2, 0, -44],
    ],
  },

  haruka: {
    id: 'haruka',
    name: 'Mt. Haruka Pass',
    nameJp: '榛香峠',
    theme: 'mountain',
    blurb: 'Two lanes of cold tarmac, a guardrail, and a drop. Grip changes with '
      + 'the elevation and the weather changes with the hour.',
    difficulty: 3,
    seed: 4021,
    width: [8.6, 8.2, 9.0, 8.4, 8.0, 8.8, 8.2, 8.6],
    shoulder: 1.1,
    wallDistance: 0.9,
    banking: 0.45,
    shoulderSurface: 'dirt',
    offTrackSurface: 'dirt',
    roadColor: [0.062, 0.065, 0.068],
    shoulderColor: [0.08, 0.075, 0.06],
    defaultTime: 1.5,
    defaultWeather: 'clear',
    points: [
      [0, 0, 0], [26, 3, 58], [74, 8, 98], [136, 14, 112], [180, 20, 84],
      [192, 26, 32], [162, 32, -8], [110, 37, -24], [68, 43, -54],
      [78, 49, -104], [128, 55, -132], [182, 59, -122], [214, 56, -74],
      [232, 49, -18], [230, 41, 42], [198, 34, 92], [148, 28, 132],
      [88, 22, 152], [28, 16, 142], [-22, 10, 112], [-42, 5, 62], [-26, 1, 20],
    ],
  },

  docks: {
    id: 'docks',
    name: 'Yokohama Docks',
    nameJp: '横浜埠頭',
    theme: 'industrial',
    blurb: 'Container stacks, floodlights and a long back straight. Wide enough '
      + 'to carry a stupid amount of angle.',
    difficulty: 2,
    seed: 7703,
    width: [17, 15, 14, 18, 20, 15, 14, 16],
    shoulder: 3.0,
    wallDistance: 2.2,
    banking: 0.2,
    shoulderSurface: 'concrete',
    offTrackSurface: 'dirt',
    roadColor: [0.09, 0.09, 0.094],
    shoulderColor: [0.13, 0.13, 0.135],
    defaultTime: 20.0,
    defaultWeather: 'wet',
    points: [
      [0, 0, 0], [0, 0, 78], [-8, 0, 150], [-46, 0, 196], [-108, 0, 208],
      [-160, 0, 182], [-176, 0, 130], [-152, 0, 84], [-104, 0, 62],
      [-96, 0, 16], [-128, 0, -22], [-186, 0, -34], [-232, 0, -70],
      [-224, 0, -126], [-172, 0, -152], [-108, 0, -148], [-52, 0, -158],
      [-8, 0, -128], [10, 0, -78], [6, 0, -34],
    ],
  },
};

export const TRACK_ORDER = ['shibuya', 'docks', 'haruka'];

export function getTrack(id) {
  return TRACKS[id] || TRACKS.shibuya;
}
