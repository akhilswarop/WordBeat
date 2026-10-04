// Run with: node tests/pure-functions.test.js
// Pure helpers from index.html, loaded without a browser (see load-app.js).
const { describe, it } = require("node:test");
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

describe("markdown helpers", () => {
  it("detects Markdown structure", () => {
    assert.equal(app.looksLikeMarkdown("# Title\n\n- a\n- b"), true);
    assert.equal(app.looksLikeMarkdown("just text."), false);
  });

  it("strips Markdown markers down to the words", () => {
    assert.equal(app.stripMarkdown("**bold** and [link](http://x.com) and `code`"), "bold and link and code");
  });
});
