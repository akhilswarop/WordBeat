// Run with: node tests/themes.test.js
// Checks every theme's colour tokens against the WCAG contrast targets.
const { describe, it } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");

function readThemeTokens() {
  const themes = {};
  const block = /:root(?:\[data-theme="(\w+)"\])?\s*\{([^}]*)\}/g;
  for (const [, name = "dark", body] of html.matchAll(block)) {
    themes[name] = { ...themes[name] };
    for (const [, key, value] of body.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6}|\d+%)\s*;/g)) {
      themes[name][key] = value;
    }
  }
  const base = themes.dark;
  for (const name of Object.keys(themes)) themes[name] = { ...base, ...themes[name] };
  return themes;
}

function luminance(hex) {
  const channel = (i) => {
    const v = parseInt(hex.slice(i, i + 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const themes = readThemeTokens();

// [foreground token, background token, minimum ratio, what it is]
const PAIRS = [
  ["text", "paper", 4.5, "body text on the page"],
  ["text", "bg", 4.5, "top bar text"],
  ["muted", "paper", 4.5, "secondary text on the page"],
  ["muted", "bg", 4.5, "secondary text on the top bar"],
  ["muted", "chip", 4.5, "secondary text on chips"],
  ["accent", "paper", 4.5, "accent text and links"],
  ["accent", "bg", 4.5, "accent text on the top bar"],
  ["accent-ink", "accent", 4.5, "icon on the play button"],
  ["text", "sentence", 4.5, "text inside the sentence band"],
  ["word-ink", "word", 4.5, "the spoken word"],
  ["word-edge", "paper", 3, "the bar under the spoken word"],
];

// The dimmed text of focus mode: --dim-mix percent of the text colour over the page.
function dimmedText(tokens) {
  const share = parseInt(tokens["dim-mix"], 10) / 100;
  const channel = (hex, i) => parseInt(hex.slice(i, i + 2), 16);
  return "#" + [1, 3, 5].map((i) => Math.round(channel(tokens.text, i) * share + channel(tokens.paper, i) * (1 - share)).toString(16).padStart(2, "0")).join("");
}

describe("theme tokens", () => {
  it("defines the four themes", () => {
    assert.deepEqual(Object.keys(themes).sort(), ["dark", "light", "oled", "sepia"]);
  });

  for (const [theme, tokens] of Object.entries(themes)) {
    it(`${theme}: focus-mode dimmed text is at least 4.5:1`, () => {
      const ratio = contrast(dimmedText(tokens), tokens.paper);
      assert.ok(ratio >= 4.5, `dimmed text is ${ratio.toFixed(2)}:1`);
    });

    for (const [fg, bg, minimum, what] of PAIRS) {
      it(`${theme}: ${what} is at least ${minimum}:1`, () => {
        const ratio = contrast(tokens[fg], tokens[bg]);
        assert.ok(ratio >= minimum, `--${fg} on --${bg} is ${ratio.toFixed(2)}:1`);
      });
    }
  }
});
