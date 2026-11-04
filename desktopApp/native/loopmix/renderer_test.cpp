// SPDX-License-Identifier: GPL-3.0-or-later
#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {
constexpr int sampleRate = 48000;
void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}
void near(double actual, double expected, double tolerance, const char* message) {
  require(std::abs(actual - expected) <= tolerance, message);
}
void fill(Deck& deck, double frequency, float gain) {
  deck.left.resize(sampleRate * 4);
  deck.right.resize(deck.left.size());
  for (size_t i = 0; i < deck.left.size(); ++i) {
    deck.left[i] = float(0.15 * std::sin(6.283185307179586 * frequency * i / sampleRate));
    deck.right[i] = 0.5f * deck.left[i];
  }
  deck.regionStart = 0;
  deck.loopStart = sampleRate / 2;
  deck.loopEnd = sampleRate;
  deck.releaseAt = sampleRate * 2;
  deck.knotTimes = {0, double(sampleRate * 2)};
  deck.knotPositions = {double(sampleRate / 2), double(sampleRate * 5 / 2)};
  deck.runOn = true;
  deck.stopAt = 3;
  deck.gain.times = {0, 3};
  deck.gain.values = {gain, gain};
}

void envelopes() {
  Envelope gain{{0, 1, 2}, {0, 0.5f, 1}, false};
  near(gain.at(-1), 0, 0, "gain before first point");
  near(gain.at(0.5), 0.25, 1e-7, "linear gain interpolation");
  near(gain.at(3), 1, 0, "gain after last point");
  Envelope filter{{0, 1}, {20, 2000}, true};
  near(filter.at(0.5), 200, 1e-4, "log-frequency interpolation");
}

void loopPositions() {
  Deck deck;
  deck.left.resize(sampleRate);
  deck.regionStart = 12;
  deck.loopStart = 4800;
  deck.loopEnd = 9600;
  deck.releaseAt = 19200;
  require(deck.sampleAt(-1) == -1, "negative input position");
  require(deck.sampleAt(9600) == 4800, "loop seam did not wrap");
  require(deck.sampleAt(19199) == 9599, "last wrapped sample");
  require(deck.sampleAt(19200) == 9600, "loop did not release at its end");
  near(deck.trackSecondsAt(19200, sampleRate), 12.2, 1e-10, "released track position");
  near(deck.releasedPositionOf(12.45, sampleRate), 31200, 1e-6, "promotion position inverse");
  require(deck.sampleAt(100000) == -1, "position outside decoded region");
}

void emptyAndMuted() {
  Renderer empty(sampleRate);
  empty.prepare();
  std::vector<float> samples(512 * 2, 99.f);
  empty.render(0, samples.data(), 512);
  require(std::all_of(samples.begin(), samples.end(), [](float x) { return x == 0; }), "empty graph not silent");
  Renderer muted(sampleRate);
  fill(muted.deck(0), 110, 0);
  fill(muted.deck(1), 165, 0);
  muted.prepare();
  for (int64_t frame = muted.firstFrame(); frame < sampleRate; frame += 512) {
    muted.render(frame, samples.data(), 512);
    require(std::all_of(samples.begin(), samples.end(), [](float x) { return x == 0; }), "closed gain leaked PCM");
  }
}

void actualStereoRendering() {
  Renderer renderer(sampleRate);
  fill(renderer.deck(0), 110, 0.5f);
  fill(renderer.deck(1), 165, 0.5f);
  renderer.prepare();
  require(renderer.firstFrame() < 0, "renderer omitted native pre-roll");
  std::vector<float> samples(512 * 2);
  double peak = 0;
  double stereoError = 0;
  int active = 0;
  const int64_t end = sampleRate * 4;
  for (int64_t frame = renderer.firstFrame(); frame < end; frame += 512) {
    const int count = int(std::min<int64_t>(512, end - frame));
    renderer.render(frame, samples.data(), count);
    for (int i = 0; i < count; ++i) {
      const float left = samples[2 * i], right = samples[2 * i + 1];
      require(std::isfinite(left) && std::isfinite(right), "nonfinite native PCM");
      peak = std::max(peak, double(std::abs(left)));
      stereoError = std::max(stereoError, std::abs(double(right) - double(left) * 0.5));
      if (std::abs(left) > 1e-5) ++active;
      if (frame >= sampleRate * 3) require(left == 0 && right == 0, "retired deck still rendered");
    }
  }
  require(active > sampleRate, "no sustained actual stretched PCM");
  require(peak > 0.01 && peak < 0.5, "unexpected generated-fixture gain");
  require(stereoError < 1e-4, "linked stereo proportion changed");
  require(renderer.deck(0).heard.load() > 1 && renderer.deck(1).heard.load() > 1, "no observed track positions");
}
}  // namespace

int main() {
  try {
    envelopes(); loopPositions(); emptyAndMuted(); actualStereoRendering();
    std::cout << "LOOPMIX_NATIVE_TESTS passed=4 scope=generated-renderer-no-device-output\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "LOOPMIX_NATIVE_FAILURE " << error.what() << '\n';
    return 1;
  }
}
