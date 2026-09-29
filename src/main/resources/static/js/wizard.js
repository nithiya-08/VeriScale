// Tiny step-by-step wizard helper shared by the owner "Get verified" flow and the officer inspection.
// Markup: a .wz-steps container for the header, and .wz-panel elements with data-step="0..n".
'use strict';

class Wizard {
  /**
   * @param {HTMLElement} stepsEl  container for the numbered step header
   * @param {HTMLElement} panelsRoot element containing the .wz-panel sections
   * @param {string[]} names       step names shown under the dots
   * @param {(i:number)=>void} onJump called when the user taps a completed step
   */
  constructor(stepsEl, panelsRoot, names, onJump) {
    this.stepsEl = stepsEl;
    this.panelsRoot = panelsRoot;
    this.names = names;
    this.current = 0;
    this.furthest = 0;
    stepsEl.style.setProperty('--n', names.length);
    stepsEl.innerHTML = `<div class="track"><span></span></div>` + names.map((n, i) => `
      <button type="button" class="wz-step" data-go="${i}" disabled>
        <span class="dot"><span class="num">${i + 1}</span></span><div class="name">${esc(n)}</div>
      </button>`).join('');
    stepsEl.addEventListener('click', ev => {
      const b = ev.target.closest('[data-go]');
      if (b && !b.disabled && onJump) onJump(Number(b.dataset.go));
    });
  }

  /** Shows step i; slides from the left when going back. lockFrom disables jumping (e.g. after submit). */
  go(i, { lock = false } = {}) {
    const back = i < this.current;
    this.current = i;
    this.furthest = Math.max(this.furthest, i);
    this.stepsEl.querySelectorAll('.wz-step').forEach((el, n) => {
      const done = n < i || (lock && n <= i);
      el.classList.toggle('done', done);
      el.classList.toggle('active', n === i && !lock);
      el.querySelector('.dot').innerHTML = done ? '<i class="bi bi-check-lg"></i>' : `<span class="num">${n + 1}</span>`;
      el.disabled = lock || n === i || n > this.furthest;
    });
    const pct = this.names.length > 1 ? 100 * i / (this.names.length - 1) : 100;
    this.stepsEl.querySelector('.track span').style.width = pct + '%';
    this.panelsRoot.querySelectorAll(':scope .wz-panel').forEach(p => {
      const show = Number(p.dataset.step) === i;
      p.classList.remove('show', 'back');
      if (show) {
        void p.offsetWidth; // restart the slide animation
        p.classList.add('show');
        if (back) p.classList.add('back');
      }
    });
  }

  reset() {
    this.current = 0;
    this.furthest = 0;
  }
}

/** Short celebratory burst. Skipped when the user prefers reduced motion. */
function confetti(host, count = 60) {
  if (REDUCED_MOTION || !host) return;
  const colors = ['#E7A94C', '#4FC3B0', '#E2704F', '#F6F2E9', '#8FA6D6'];
  const box = document.createElement('div');
  box.className = 'confetti';
  box.setAttribute('aria-hidden', 'true');
  box.innerHTML = Array.from({ length: count }, () => {
    const left = Math.random() * 100, delay = Math.random() * .6, dur = 1.8 + Math.random() * 1.2;
    const c = colors[Math.floor(Math.random() * colors.length)];
    return `<i style="left:${left}%;background:${c};animation-delay:${delay}s;animation-duration:${dur}s"></i>`;
  }).join('');
  host.appendChild(box);
  setTimeout(() => box.remove(), 3500);
}

/** Animated green tick (circle draws, then the check). */
function successMark() {
  return `<div class="halo"><div class="vmark" aria-hidden="true"><svg viewBox="0 0 100 100" fill="none" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">
    <circle class="c" cx="50" cy="50" r="44"/><path class="p" d="M30 50 L44 64 L70 36"/></svg></div></div>`;
}
