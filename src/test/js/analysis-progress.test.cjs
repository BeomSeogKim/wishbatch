const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/analysis-progress.js'), 'utf8');
function browser({status = 'RUNNING', responses = []} = {}) {
    const node = () => ({textContent: '', hidden: true, disabled: true, children: [],
        replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); }});
    const elements = Object.fromEntries(['analysis-progress', 'progress-status', 'progress-processed', 'progress-batches',
        'progress-validation', 'progress-events', 'analysis-progress-start', 'progress-failure', 'progress-final',
        'progress-review', 'progress-feedback', 'progress-checkpoint-warning'].map(id => [id, node()]));
    elements['analysis-progress'].dataset = {status, pollUrl: '/w/token/maker/analysis-progress/job', proposalUrl: '/w/token/maker/proposal'};
    const counts = Object.fromEntries(['compatible', 'depends', 'notServed', 'notARequest'].map(key => [key, node()]));
    elements['analysis-progress'].querySelector = selector => selector === '[data-option-id="option"]'
        ? {querySelector: field => counts[field.match(/"(.*?)"/)[1]]} : null;
    const calls = [];
    const timers = [];
    const context = {
        document: {getElementById: id => elements[id], createElement: node},
        window: {setTimeout: callback => timers.push(callback)},
        fetch: async (url, options) => {
            calls.push({url, options});
            const response = responses.shift();
            if (response instanceof Error) throw response;
            return {ok: response.ok, json: async () => response.body};
        }
    };
    vm.runInNewContext(script, context);
    return {elements, calls, timers, counts, flush: () => new Promise(resolve => setImmediate(resolve))};
}
const job = (changes = {}) => ({status: 'RUNNING', processedWishes: 4, completedBatches: 2, validationCounts: {V2: 1},
    events: [{sequence: 1, stage: 'BATCH_COMPLETED', reading: 2, batch: 1, totalBatches: 10,
        processedWishes: 4, validationCounts: {V2: 1}}], finalCounts: [], runId: null, ...changes});

test('polls read only and uses the distinct processed count supplied by the server', async () => {
    const app = browser({responses: [{ok: true, body: job()}]});
    await app.flush();
    assert.equal(app.calls[0].url, '/w/token/maker/analysis-progress/job');
    assert.equal(app.calls[0].options.method, undefined);
    assert.equal(app.calls[0].options.credentials, 'same-origin');
    assert.equal(app.elements['progress-processed'].textContent, 4);
    assert.equal(app.elements['progress-batches'].textContent, 2);
    assert.equal(app.elements['progress-validation'].children[0].textContent, 'V2: 1');
    assert.match(app.elements['progress-events'].children[0].textContent, /reading 2.*batch 1\/10.*4 distinct wishes.*V2: 1/);
    assert.equal(app.elements['progress-final'].hidden, true);
    assert.equal(app.timers.length, 1);
});

test('only completed analysis exposes final validated counts and its scoped review link', async () => {
    const app = browser({responses: [{ok: true, body: job({status: 'COMPLETED', runId: 'run/id',
        finalCounts: [{optionId: 'option', compatible: 2, depends: 1, notServed: 0, notARequest: 1}]})}]});
    await app.flush();
    assert.equal(app.elements['progress-final'].hidden, false);
    assert.equal(app.elements['progress-failure'].hidden, true);
    assert.equal(app.elements['progress-review'].href, '/w/token/maker/proposal?run=run%2Fid');
    assert.equal(app.counts.compatible.textContent, 2);
    assert.equal(app.counts.depends.textContent, 1);
    assert.equal(app.elements['analysis-progress-start'].disabled, false);
    assert.equal(app.timers.length, 0);
});

test('failed and interrupted attempts stop polling and retain the previous-analysis explanation', async () => {
    for (const status of ['FAILED', 'INTERRUPTED']) {
        const app = browser({responses: [{ok: true, body: job({status})}]});
        await app.flush();
        assert.equal(app.elements['progress-failure'].hidden, false);
        assert.equal(app.elements['progress-final'].hidden, true);
        assert.equal(app.timers.length, 0);
    }
});

test('network and access failures pause updates without retrying or claiming completion', async () => {
    for (const response of [new Error('Disconnected'), {ok: false}]) {
        const app = browser({responses: [response]});
        await app.flush();
        assert.match(app.elements['progress-feedback'].textContent, /Live updates paused.*Refresh/);
        assert.equal(app.elements['progress-final'].hidden, true);
        assert.equal(app.timers.length, 0);
    }
});

test('validator event text is displayed literally without HTML insertion', async () => {
    const app = browser({responses: [{ok: true, body: job({events: [{sequence: 1, stage: '<script>bad()</script>',
        processedWishes: 4, validationCounts: {}}]})}]});
    await app.flush();
    assert.match(app.elements['progress-events'].children[0].textContent, /<script>bad\(\)<\/script>/);
    assert.equal(app.elements['progress-events'].children[0].innerHTML, undefined);
});

test('incomplete checkpoints disclose unknown recorded validation counts', async () => {
    const app = browser({responses: [{ok: true, body: job({checkpointIncomplete: true})}]});
    await app.flush();
    assert.equal(app.elements['progress-checkpoint-warning'].hidden, false);
    assert.equal(app.elements['progress-final'].hidden, true);
});

test('terminal server-rendered pages make no polling request', () => {
    for (const status of ['COMPLETED', 'FAILED', 'INTERRUPTED']) {
        const app = browser({status});
        assert.equal(app.calls.length, 0);
        assert.equal(app.timers.length, 0);
    }
});

test('proposal busy status only follows a non-prevented analysis submission and resets on return', () => {
    const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/maker/proposal.html'), 'utf8');
    const inline = template.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
    assert.match(template, /id="analysis-working"[^>]*hidden><\/p>/);
    const handlers = {};
    const windowHandlers = {};
    const tasks = [];
    const form = {attrs: {}, addEventListener: (type, fn) => { handlers[type] = fn; },
        setAttribute(name, value) { this.attrs[name] = value; }, removeAttribute(name) { delete this.attrs[name]; }};
    const button = {disabled: false, textContent: 'Run it live'};
    const working = {hidden: true, textContent: ''};
    const elements = {'analysis-form': form, 'analysis-submit': button, 'analysis-working': working};
    const context = {document: {addEventListener() {}, getElementById: id => elements[id]},
        window: {location: {hash: ''}, addEventListener: (type, fn) => { windowHandlers[type] = fn; }},
        queueMicrotask: fn => tasks.push(fn)};
    vm.runInNewContext(inline, context);
    handlers.submit({target: {}, defaultPrevented: false});
    assert.equal(tasks.length, 0);
    const canceled = {target: form, defaultPrevented: false};
    handlers.submit(canceled);
    canceled.defaultPrevented = true;
    tasks.shift()();
    assert.equal(working.textContent, '');
    assert.equal(button.disabled, false);
    handlers.submit({target: form, defaultPrevented: false});
    tasks.shift()();
    assert.match(working.textContent, /Reading wishes/);
    assert.equal(working.hidden, false);
    assert.equal(form.attrs['aria-busy'], 'true');
    windowHandlers.pageshow();
    assert.equal(working.textContent, '');
    assert.equal(working.hidden, true);
    assert.equal(button.disabled, false);
    assert.equal(button.textContent, 'Run it live');
    assert.equal(form.attrs['aria-busy'], undefined);
});
