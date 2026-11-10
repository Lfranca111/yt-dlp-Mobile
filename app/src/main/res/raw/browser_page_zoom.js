(function (direction) {
    if (!/^https?:$/.test(location.protocol) || !document.documentElement ||
        document.documentElement.localName !== "html" || !window.CSS ||
        !CSS.supports("zoom", "0.8")) return -1;

    const root = document.documentElement;
    const key = Symbol.for("com.linfranca.ytdlpmobile.browserPageZoom");
    const levels = [50, 60, 70, 80, 90, 100, 110, 125, 150, 175, 200];
    let state = root[key];
    const previous = state ? state.percent : 100;
    const index = levels.indexOf(previous);
    const percent = direction === 0 ? 100 :
        levels[Math.max(0, Math.min(levels.length - 1, index + direction))];
    if (percent === previous) return percent;

    // Scroll APIs use document CSS coordinates. Keep the same point in view
    // across reflow, clamped naturally when it no longer fits the document.
    const x = window.scrollX;
    const y = window.scrollY;
    if (percent === 100) {
        if (root.style.getPropertyValue("zoom") === state.applied &&
            root.style.getPropertyPriority("zoom") === "important") {
            if (state.value) root.style.setProperty("zoom", state.value, state.priority);
            else root.style.removeProperty("zoom");
        }
        delete root[key];
    } else {
        if (!state || root.style.getPropertyValue("zoom") !== state.applied ||
            root.style.getPropertyPriority("zoom") !== "important") {
            const computed = getComputedStyle(root).zoom || "1";
            const number = parseFloat(computed) / (computed.endsWith("%") ? 100 : 1);
            state = { value: root.style.getPropertyValue("zoom"),
                priority: root.style.getPropertyPriority("zoom"),
                base: Number.isFinite(number) && number > 0 ? number : 1 };
        }
        root.style.setProperty("zoom", String(state.base * percent / 100), "important");
        state.applied = root.style.getPropertyValue("zoom");
        state.percent = percent;
        root[key] = state;
    }
    window.scrollTo({left: x, top: y, behavior: "instant"});
    return percent;
})
