// Boots index.html's inline <script> in a Node vm with a permissive DOM stub,
// so tests can call the page's top-level functions without a browser.
const fs = require("fs");
const path = require("path");
const vm = require("vm");

function makeStub() {
  const target = function () {};
  const handler = {
    get(_t, prop) {
      if (prop === Symbol.toPrimitive) return () => "";
      if (prop === "then") return undefined;
      if (prop === "length") return 0;
      if (prop === Symbol.iterator) return function* () {};
      return makeStub();
    },
    set() { return true; },
    apply() { return makeStub(); },
    construct() { return makeStub(); },
    has() { return true; },
  };
  return new Proxy(target, handler);
}

function loadApp() {
  const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
  const start = html.indexOf("<script>") + "<script>".length;
  const end = html.lastIndexOf("</script>");
  const script = html.slice(start, end);

  const stub = makeStub();
  const sandbox = {
    console,
    setTimeout, clearTimeout, setInterval, clearInterval,
    performance, URL, TextEncoder, TextDecoder, AbortSignal, Promise,
    document: stub, navigator: stub, localStorage: stub, location: { protocol: "http:" },
    matchMedia: () => stub, requestAnimationFrame: () => 0, cancelAnimationFrame: () => {},
    Intl, Node: { COMMENT_NODE: 8, TEXT_NODE: 3, ELEMENT_NODE: 1 },
    Event: function () {}, DOMParser: function () { return stub; },
    SpeechSynthesisUtterance: function () {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent() {},
    devicePixelRatio: 1, innerWidth: 1024, innerHeight: 768, speechSynthesis: stub,
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox, { filename: "index.html<script>" });
  return sandbox;
}

module.exports = { loadApp };
