import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import JSZip from "jszip";
import sharp from "sharp";

const source = "/Users/ryoto/code/java-learning/shohko_birthday_quiz_2026_timer_reset.pptx";
const out = "/Users/ryoto/code/java-learning/.codex-tmp/birthday-quiz-compress/media-inventory.json";

function resolveTarget(baseName, target) {
  return path.posix.normalize(path.posix.join(path.posix.dirname(baseName), target));
}

function parseRelationships(xml, relsName) {
  const map = new Map();
  const pattern = /<Relationship\b[^>]*\/?>(?:<\/Relationship>)?/g;
  for (const match of xml.matchAll(pattern)) {
    const id = /\bId="([^"]+)"/.exec(match[0])?.[1];
    const target = /\bTarget="([^"]+)"/.exec(match[0])?.[1];
    const type = /\bType="([^"]+)"/.exec(match[0])?.[1];
    if (!id || !target || !type) continue;
    const slideName = relsName.replace("ppt/slides/_rels/", "ppt/slides/").replace(".rels", "");
    map.set(id, { target: resolveTarget(slideName, target), type });
  }
  return map;
}

async function main() {
  const zip = await JSZip.loadAsync(await fs.readFile(source));
  const usages = new Map();
  for (let slide = 1; slide <= 28; slide += 1) {
    const slideName = `ppt/slides/slide${slide}.xml`;
    const relsName = `ppt/slides/_rels/slide${slide}.xml.rels`;
    if (!zip.file(slideName) || !zip.file(relsName)) continue;
    const [slideXml, relsXml] = await Promise.all([
      zip.file(slideName).async("string"),
      zip.file(relsName).async("string"),
    ]);
    const rels = parseRelationships(relsXml, relsName);
    const blocks = [...slideXml.matchAll(/<p:pic\b[\s\S]*?<\/p:pic>/g)].map((m) => m[0]);
    for (const block of blocks) {
      const rid = /<a:blip\b[^>]*\br:embed="([^"]+)"/.exec(block)?.[1];
      if (!rid || !rels.has(rid)) continue;
      const rel = rels.get(rid);
      const ext = /<a:ext\b[^>]*\bcx="(\d+)"[^>]*\bcy="(\d+)"/.exec(block);
      const srcRectTag = /<a:srcRect\b[^>]*\/?>(?:<\/a:srcRect>)?/.exec(block)?.[0] || "";
      const crop = {
        left: Number(/\bl="(\d+)"/.exec(srcRectTag)?.[1] || 0) / 100000,
        top: Number(/\bt="(\d+)"/.exec(srcRectTag)?.[1] || 0) / 100000,
        right: Number(/\br="(\d+)"/.exec(srcRectTag)?.[1] || 0) / 100000,
        bottom: Number(/\bb="(\d+)"/.exec(srcRectTag)?.[1] || 0) / 100000,
      };
      const entry = {
        slide,
        rid,
        cx: ext ? Number(ext[1]) : null,
        cy: ext ? Number(ext[2]) : null,
        displayInches: ext ? [Number(ext[1]) / 914400, Number(ext[2]) / 914400] : null,
        displayPx96: ext ? [Number(ext[1]) / 9525, Number(ext[2]) / 9525] : null,
        crop,
        visibleFraction: [1 - crop.left - crop.right, 1 - crop.top - crop.bottom],
      };
      if (!usages.has(rel.target)) usages.set(rel.target, []);
      usages.get(rel.target).push(entry);
    }
    for (const match of slideXml.matchAll(/<a:blip\b[^>]*\br:embed="([^"]+)"/g)) {
      const rel = rels.get(match[1]);
      if (!rel || !rel.target.startsWith("ppt/media/")) continue;
      if (!usages.has(rel.target)) usages.set(rel.target, []);
      if (!usages.get(rel.target).some((u) => u.slide === slide && u.rid === match[1])) {
        usages.get(rel.target).push({ slide, rid: match[1], cx: null, cy: null, displayInches: null, displayPx96: null });
      }
    }
  }

  const mediaNames = Object.keys(zip.files).filter((name) => name.startsWith("ppt/media/") && !name.endsWith("/"));
  const media = [];
  for (const name of mediaNames) {
    const bytes = await zip.file(name).async("nodebuffer");
    let metadata = {};
    let error = null;
    try {
      metadata = await sharp(bytes, { animated: true }).metadata();
    } catch (e) {
      error = String(e.message || e);
    }
    media.push({
      name,
      bytes: bytes.length,
      sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
      format: metadata.format || path.extname(name).slice(1),
      width: metadata.width || null,
      height: metadata.pageHeight || metadata.height || null,
      frames: metadata.pages || 1,
      totalHeight: metadata.height || null,
      density: metadata.density || null,
      error,
      usages: usages.get(name) || [],
    });
  }
  media.sort((a, b) => b.bytes - a.bytes);
  const summary = {
    source,
    pptxBytes: (await fs.stat(source)).size,
    mediaBytes: media.reduce((sum, item) => sum + item.bytes, 0),
    mediaCount: media.length,
    media,
  };
  await fs.writeFile(out, JSON.stringify(summary, null, 2));
  console.log(JSON.stringify({ pptxBytes: summary.pptxBytes, mediaBytes: summary.mediaBytes, mediaCount: summary.mediaCount, largest: media.slice(0, 20) }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
