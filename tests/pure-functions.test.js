// Run with: node tests/pure-functions.test.js
// Pure helpers from index.html, loaded without a browser (see load-app.js).
const { describe, it, afterEach } = require("node:test");
const assert = require("node:assert/strict");
const { loadApp } = require("./load-app.js");

const app = loadApp();
// Values built inside the vm context have a different Object/Array prototype.
const plain = (value) => JSON.parse(JSON.stringify(value));

describe("tokenize", () => {
  const text = "Hello, world! It’s 3.5 ok.";

  it("records offsets that slice back to each word", () => {
    for (const token of plain(app.tokenize(text))) {
      assert.equal(text.slice(token.start, token.end), token.text);
    }
  });

  it("keeps apostrophes and decimals inside one word", () => {
    const words = plain(app.tokenize(text)).map((t) => t.text);
    assert.deepEqual(words, ["Hello", "world", "It’s", "3.5", "ok"]);
  });
});

describe("chunkText", () => {
  it("splits on sentence boundaries", () => {
    const chunks = plain(app.chunkText("One. Two three four. Five!", 12));
    assert.deepEqual(chunks.map((c) => c.text), ["One.", " Two three four.", " Five!"]);
  });

  it("reassembles to the original text from charOffset", () => {
    const source = "word ".repeat(60).trim();
    const rebuilt = plain(app.chunkText(source, 40)).map((c) => c.text).join("");
    assert.equal(rebuilt, source);
  });

  it("gives every chunk the offset where its text starts", () => {
    const source = "First sentence here. Second one follows. A third to finish!";
    for (const chunk of plain(app.chunkText(source, 25))) {
      assert.equal(source.slice(chunk.charOffset, chunk.charOffset + chunk.text.length), chunk.text);
    }
  });
});

describe("stripNoisySymbols", () => {
  it("never changes the text length, so word offsets stay valid", () => {
    const text = "my_variable-name = {x} // a.b, c! #tag — ok";
    assert.equal(app.stripNoisySymbols(text).length, text.length);
  });

  it("turns identifier punctuation into spaces", () => {
    assert.equal(app.stripNoisySymbols("my_variable-name"), "my variable name");
  });

  it("keeps punctuation the voice uses for phrasing", () => {
    const text = "Wait, what? Yes! It’s (really) fine; ok: done.";
    assert.equal(app.stripNoisySymbols(text), text);
  });
});

describe("fmtTime", () => {
  it("formats minutes and seconds", () => {
    assert.equal(app.fmtTime(61), "1:01");
    assert.equal(app.fmtTime(3725), "62:05");
  });

  it("rounds to the nearest second", () => {
    assert.equal(app.fmtTime(59.6), "1:00");
  });

  it("clamps negatives to zero", () => {
    assert.equal(app.fmtTime(-5), "0:00");
  });
});

describe("countWords", () => {
  it("counts whitespace-separated words", () => {
    assert.equal(app.countWords(" a  b\nc "), 3);
  });

  it("counts none in an empty string", () => {
    assert.equal(app.countWords(""), 0);
  });
});

describe("looksLikeBareUrl", () => {
  it("accepts a lone http(s) link, ignoring surrounding whitespace", () => {
    assert.equal(app.looksLikeBareUrl(" https://a.com/x \n"), true);
  });

  it("rejects a sentence that contains a link", () => {
    assert.equal(app.looksLikeBareUrl("see https://a.com"), false);
  });

  it("rejects non-http schemes and plain text", () => {
    assert.equal(app.looksLikeBareUrl("ftp://a.com"), false);
    assert.equal(app.looksLikeBareUrl("not a url"), false);
  });
});

describe("extractSharedUrl", () => {
  it("returns a bare link as is", () => {
    assert.equal(app.extractSharedUrl("https://a.com/x"), "https://a.com/x");
  });

  it("finds the link in a title-plus-link share", () => {
    assert.equal(app.extractSharedUrl("Cool Title https://share.google/abc"), "https://share.google/abc");
  });

  it("drops trailing sentence punctuation from the link", () => {
    assert.equal(app.extractSharedUrl("Read this https://a.com/p."), "https://a.com/p");
  });

  it("returns null when there is no link", () => {
    assert.equal(app.extractSharedUrl("no link here"), null);
  });

  it("returns null for a long passage that merely cites a link", () => {
    assert.equal(app.extractSharedUrl("word ".repeat(60) + "https://a.com/x"), null);
  });
});

describe("looksBlocked", () => {
  it("recognizes bot-wall pages", () => {
    assert.equal(app.looksBlocked("Just a moment..."), true);
    assert.equal(app.looksBlocked("403 ERROR Request blocked."), true);
  });

  it("lets ordinary article text through", () => {
    assert.equal(app.looksBlocked("Real article text about cats."), false);
  });
});

describe("looksPaywalled", () => {
  const paragraph = (textContent) => ({ textContent });
  const treeOf = (...paragraphs) => ({ querySelectorAll: () => paragraphs.map(paragraph) });
  const filler = (words) => Array(words).fill("word").join(" ");

  it("flags an explicit member-only marker", () => {
    const text = `Member-only story ${filler(50)}.`;
    assert.equal(app.looksPaywalled({ text, tree: treeOf(text) }), true);
  });

  it("flags a short preview that stops mid-sentence", () => {
    const text = `${filler(50)} health and readiness`;
    assert.equal(app.looksPaywalled({ text, tree: treeOf(text) }), true);
  });

  it("does not flag a short article that ends on a byline", () => {
    const body = "The studio said it is preparing for a future built with new tools.";
    const text = `${body} Terrence O'Brien`;
    assert.equal(app.looksPaywalled({ text, tree: treeOf(body, "Terrence O'Brien") }), false);
  });

  it("does not flag a long article regardless of its last character", () => {
    const text = filler(450);
    assert.equal(app.looksPaywalled({ text, tree: treeOf(text) }), false);
  });
});

describe("trimLeadingBoilerplate", () => {
  const sentence = "This is a long sentence with many words that makes it count as real prose for sure.";
  const prose = Array(4).fill(sentence).join("\n\n");

  it("drops leading menus and keeps the article", () => {
    const menu = "# Menu\n\n* Home\n* World\n* Sport\n\n";
    assert.equal(app.trimLeadingBoilerplate(menu + prose), prose);
  });

  it("leaves text that already starts with prose alone", () => {
    assert.equal(app.trimLeadingBoilerplate(prose), prose);
  });

  it("leaves text with no prose at all alone", () => {
    const menuOnly = "# Menu\n\n* Home\n* World";
    assert.equal(app.trimLeadingBoilerplate(menuOnly), menuOnly);
  });
});

describe("detectFormat", () => {
  const file = (name, type = "") => ({ name, type });
  const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  it("recognizes each supported format by extension", () => {
    assert.equal(app.detectFormat(file("a.docx")), "docx");
    assert.equal(app.detectFormat(file("a.epub")), "epub");
    assert.equal(app.detectFormat(file("a.pdf")), "pdf");
    assert.equal(app.detectFormat(file("a.JPG")), "image");
    assert.equal(app.detectFormat(file("a.md")), "text");
  });

  it("falls back to the MIME type when the name has no extension", () => {
    assert.equal(app.detectFormat(file("shared", DOCX)), "docx");
    assert.equal(app.detectFormat(file("shared", "application/pdf")), "pdf");
    assert.equal(app.detectFormat(file("shared", "image/png")), "image");
    assert.equal(app.detectFormat(file("shared", "text/plain")), "text");
  });

  it("returns null for anything unsupported", () => {
    assert.equal(app.detectFormat(file("tool.exe", "application/octet-stream")), null);
    assert.equal(app.detectFormat(file("anim.gif", "image/gif")), null);
  });

  it("lets an earlier format claim a file before text does", () => {
    assert.equal(app.detectFormat(file("scan.pdf", "text/plain")), "pdf");
  });
});

describe("createPlaybackClock", () => {
  // The clock only needs a time source, a playing flag, a rate and two text
  // targets, so it is built here with all four faked.
  const created = [];
  // A started clock owns a real interval that would keep Node alive.
  afterEach(() => { created.splice(0).forEach((clock) => clock.stop()); });

  function makeClock({ rate = 1 } = {}) {
    const time = { now: 1000 };
    const state = { playing: false };
    const elapsedEl = { textContent: "" };
    const totalEl = { textContent: "" };
    const clock = app.createPlaybackClock({
      isPlaying: () => state.playing,
      getRate: () => rate,
      elapsedEl,
      totalEl,
      now: () => time.now,
    });
    created.push(clock);
    return { clock, time, state, elapsedEl, totalEl };
  }

  it("counts only time spent playing", () => {
    const { clock, time, state, elapsedEl } = makeClock();
    state.playing = true; clock.start();
    time.now += 5000;
    state.playing = false; clock.stop();
    time.now += 60000;   // paused for a minute
    clock.render();
    assert.equal(elapsedEl.textContent, "0:05");
  });

  it("does not bank time since page load when stopped before ever starting", () => {
    const { clock, time, elapsedEl } = makeClock();
    time.now = 5_000_000;   // long after load, segmentStart still at its initial 0
    clock.stop();
    assert.equal(elapsedEl.textContent, "0:00");
  });

  it("resumes from the banked time after a pause", () => {
    const { clock, time, state, elapsedEl } = makeClock();
    state.playing = true; clock.start();
    time.now += 3000;
    state.playing = false; clock.stop();
    state.playing = true; clock.start();
    time.now += 4000;
    clock.render();
    assert.equal(elapsedEl.textContent, "0:07");
  });

  it("estimates the total from the fixed words-per-second guess until it can measure", () => {
    const { clock, totalEl } = makeClock({ rate: 1 });
    clock.setProgress(0, 290);   // 290 words at 2.9 words/s
    clock.render();
    assert.equal(totalEl.textContent, "1:40");
  });

  it("scales the guess by playback rate", () => {
    const { clock, totalEl } = makeClock({ rate: 2 });
    clock.setProgress(0, 290);
    clock.render();
    assert.equal(totalEl.textContent, "0:50");
  });

  it("extrapolates the total from measured pace once enough has played", () => {
    const { clock, time, state, totalEl } = makeClock();
    state.playing = true; clock.start();
    time.now += 10_000;                 // 10 s elapsed
    clock.setProgress(50, 200);         // a quarter done, so about 40 s in all
    clock.render();
    assert.equal(totalEl.textContent, "0:40");
  });

  it("returns to zero on reset", () => {
    const { clock, time, state, elapsedEl } = makeClock();
    state.playing = true; clock.start();
    time.now += 9000;
    state.playing = false; clock.stop();
    clock.reset();
    assert.equal(elapsedEl.textContent, "0:00");
  });
});

describe("markdown helpers", () => {
  it("detects Markdown structure", () => {
    assert.equal(app.looksLikeMarkdown("# Title\n\n- a\n- b"), true);
    assert.equal(app.looksLikeMarkdown("just text."), false);
  });

  it("strips Markdown markers down to the words", () => {
    assert.equal(app.stripMarkdown("**bold** and [link](http://x.com) and `code`"), "bold and link and code");
  });
});

describe("normalizePrefs", () => {
  const defaults = { theme: "dark", font: "serif", size: 20, spacing: "normal", width: "medium", focus: false, miniPlayer: true };

  it("returns the defaults for nothing, garbage or a non-object", () => {
    assert.deepEqual(plain(app.normalizePrefs(null)), defaults);
    assert.deepEqual(plain(app.normalizePrefs("not json")), defaults);
    assert.deepEqual(plain(app.normalizePrefs(42)), defaults);
  });

  it("accepts a JSON string or an object", () => {
    const saved = { theme: "sepia", font: "sans", size: 24, spacing: "airy", width: "wide", focus: true, miniPlayer: false };
    assert.deepEqual(plain(app.normalizePrefs(JSON.stringify(saved))), saved);
    assert.deepEqual(plain(app.normalizePrefs(saved)), saved);
  });

  it("replaces unknown values one by one", () => {
    const result = plain(app.normalizePrefs({ theme: "neon", font: "serif", size: 22, width: "huge", focus: "yes" }));
    assert.equal(result.theme, "dark");
    assert.equal(result.font, "serif");
    assert.equal(result.size, 22);
    assert.equal(result.width, "medium");
    assert.equal(result.focus, false);
  });

  it("keeps the size inside the slider range and rounds it", () => {
    assert.equal(app.normalizePrefs({ size: 4 }).size, 16);
    assert.equal(app.normalizePrefs({ size: 99 }).size, 30);
    assert.equal(app.normalizePrefs({ size: 21.6 }).size, 22);
  });

  it("ignores a size that is not a finite number", () => {
    assert.equal(app.normalizePrefs({ size: null }).size, 20);
    assert.equal(app.normalizePrefs({ size: "30" }).size, 20);
    assert.equal(app.normalizePrefs({ size: NaN }).size, 20);
  });
});

describe("resolveTheme", () => {
  it("follows the system only for auto", () => {
    assert.equal(app.resolveTheme("auto", true), "light");
    assert.equal(app.resolveTheme("auto", false), "dark");
  });

  it("returns every other choice unchanged", () => {
    assert.equal(app.resolveTheme("sepia", true), "sepia");
    assert.equal(app.resolveTheme("oled", false), "oled");
  });
});

describe("createShrinkScheduler", () => {
  // A fake clock: timers run only when the test says so.
  function makeScheduler({ canShrink = () => true } = {}) {
    const calls = [];
    const pending = new Map();
    let nextId = 1;
    const timers = {
      set: (fn, ms) => { pending.set(nextId, { fn, ms }); return nextId++; },
      clear: (id) => pending.delete(id),
    };
    const scheduler = app.createShrinkScheduler({ delayMs: 5000, setMini: (v) => calls.push(v), canShrink, timers });
    const fire = () => { for (const [id, t] of [...pending]) { pending.delete(id); t.fn(); } };
    return { scheduler, calls, pending, fire };
  }

  it("shrinks once the delay passes", () => {
    const { scheduler, calls, pending, fire } = makeScheduler();
    scheduler.schedule();
    assert.equal(pending.size, 1);
    assert.deepEqual(calls, []);
    fire();
    assert.deepEqual(calls, [true]);
  });

  it("restarts the wait when scheduled again", () => {
    const { scheduler, pending } = makeScheduler();
    scheduler.schedule();
    scheduler.schedule();
    assert.equal(pending.size, 1);
  });

  it("expands at once and cancels a pending shrink", () => {
    const { scheduler, calls, pending, fire } = makeScheduler();
    scheduler.schedule();
    scheduler.expand();
    fire();
    assert.equal(pending.size, 0);
    assert.deepEqual(calls, [false]);
  });

  it("works with the real timers when none are injected", () => {
    const scheduler = app.createShrinkScheduler({ delayMs: 60000, setMini() {} });
    scheduler.schedule();    // the real setTimeout rejects being called as a method
    scheduler.cancel();
  });

  it("does not shrink while canShrink says no", () => {
    const { scheduler, calls, fire } = makeScheduler({ canShrink: () => false });
    scheduler.schedule();
    fire();
    assert.deepEqual(calls, []);
  });
});

describe("swipeDirection", () => {
  const start = { x: 200, y: 100, t: 0 };

  it("treats a long quick leftward drag as forward", () => {
    assert.equal(app.swipeDirection(start, { x: 120, y: 105, t: 200 }), 1);
  });

  it("treats a long quick rightward drag as back", () => {
    assert.equal(app.swipeDirection(start, { x: 290, y: 95, t: 200 }), -1);
  });

  it("ignores short drags, slow drags and mostly vertical drags", () => {
    assert.equal(app.swipeDirection(start, { x: 170, y: 100, t: 100 }), 0);
    assert.equal(app.swipeDirection(start, { x: 100, y: 100, t: 1500 }), 0);
    assert.equal(app.swipeDirection(start, { x: 100, y: 180, t: 200 }), 0);
  });
});

describe("buildOutline", () => {
  const h = (level, text) => ({ level, text });

  it("returns nothing when there are too few headings to help", () => {
    assert.deepEqual(plain(app.buildOutline([h(1, "A"), h(2, "B")])), []);
  });

  it("lists headings with a depth relative to the shallowest one", () => {
    const outline = plain(app.buildOutline([h(2, "Intro"), h(3, "Detail"), h(2, "Next")]));
    assert.deepEqual(outline.map((i) => [i.text, i.depth]), [["Intro", 0], ["Detail", 1], ["Next", 0]]);
  });

  it("skips empty headings and levels deeper than three, keeping source indexes", () => {
    const outline = plain(app.buildOutline([h(1, "One"), h(4, "Deep"), h(2, "  "), h(2, "Two"), h(3, "Three")]));
    assert.deepEqual(outline.map((i) => i.index), [0, 3, 4]);
  });

  it("collapses whitespace and shortens long titles", () => {
    const outline = plain(app.buildOutline([h(1, "A\n  b"), h(1, "x".repeat(100)), h(1, "C")]));
    assert.equal(outline[0].text, "A b");
    assert.equal(outline[1].text.length, 60);
    assert.ok(outline[1].text.endsWith("…"));
  });
});

describe("normalizeTypedLink", () => {
  it("adds https to a bare domain, with or without a path", () => {
    assert.equal(app.normalizeTypedLink("example.com"), "https://example.com");
    assert.equal(app.normalizeTypedLink(" theverge.com/2024/a-story "), "https://theverge.com/2024/a-story");
  });

  it("leaves full links and ordinary text alone", () => {
    assert.equal(app.normalizeTypedLink("http://a.com/x"), "http://a.com/x");
    assert.equal(app.normalizeTypedLink("just some words"), "just some words");
  });
});

describe("recent items", () => {
  const link = (url, at = 1) => ({ kind: "link", url, title: "T", source: "a.com", words: 600, at });
  const text = (body) => ({ kind: "text", title: "T", source: "pasted text", text: body, words: 100 });

  it("puts the newest first and keeps one entry per document", () => {
    const list = app.addRecent([link("https://a.com/1"), link("https://a.com/2")], link("https://a.com/1"));
    assert.deepEqual(plain(list).map((e) => e.url), ["https://a.com/1", "https://a.com/2"]);
  });

  it("keeps at most five", () => {
    let list = [];
    for (let i = 0; i < 8; i++) list = app.addRecent(list, link(`https://a.com/${i}`));
    assert.equal(list.length, 5);
    assert.equal(list[0].url, "https://a.com/7");
  });

  it("treats the same text as one entry", () => {
    const body = "word ".repeat(40);
    assert.equal(app.addRecent([text(body)], text(body)).length, 1);
  });

  it("drops malformed entries when reading storage", () => {
    const stored = JSON.stringify([link("https://a.com/ok"), link("ftp://a.com/x"), { kind: "link" }, text(""), null, 5]);
    assert.deepEqual(plain(app.normalizeRecents(stored)).map((e) => e.url), ["https://a.com/ok"]);
  });

  it("returns an empty list for garbage", () => {
    assert.deepEqual(plain(app.normalizeRecents("not json")), []);
    assert.deepEqual(plain(app.normalizeRecents({})), []);
  });

  it("describes an entry by source and read time", () => {
    assert.equal(app.recentMeta({ source: "a.com", words: 1740 }), "a.com · 10 min");
    assert.equal(app.recentMeta({ source: "notes.md", words: 20 }), "notes.md · 1 min");
  });
});

describe("titleFromText", () => {
  it("uses the first non-empty line without heading marks", () => {
    assert.equal(app.titleFromText("\n\n## Big idea\nbody"), "Big idea");
  });

  it("shortens a long first line", () => {
    const title = app.titleFromText("x".repeat(100));
    assert.equal(title.length, 60);
    assert.ok(title.endsWith("…"));
  });
});
