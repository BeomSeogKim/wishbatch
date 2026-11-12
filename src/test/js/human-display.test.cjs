const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window: {}};
vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/human-display.js', 'utf8'), context);
const display = context.window.WishbatchDisplay;

test('timestamps use UTC minutes without seconds or microseconds', () => {
    assert.equal(display.time('2026-11-12T23:05:59.123456+09:00'), '12 Nov 2026, 14:05 UTC');
    assert.equal(display.time(null), 'Not available');
    assert.equal(display.time('invalid'), 'Not available');
});

test('money preserves exact integer minor units across currency precision', () => {
    assert.equal(display.money('9223372036854775807', 'USD'), 'USD 92233720368547758.07');
    assert.equal(display.money(123456789012345678901234567890n, 'USD'), 'USD 1234567890123456789012345678.90');
    assert.equal(display.money(-1, 'USD'), 'USD -0.01');
    assert.equal(display.money(0, 'USD'), 'USD 0.00');
    assert.equal(display.money('123', 'JPY'), 'JPY 123');
    assert.equal(display.money('123', 'KWD'), 'KWD 0.123');
    for (const value of [9007199254740992, 1.5, '1.50', null, true]) {
        assert.throws(() => display.money(value, 'USD'), /Exact minor units required/);
    }
});

test('state labels preserve payment distinctions without inferring an outcome', () => {
    const expected = {
        FUNDED: 'Funded', NOT_FUNDED: 'Not funded',
        AUTHORIZED: 'Hold placed', CAPTURED: 'Paid', CAPTURE_PENDING: 'Payment pending',
        CAPTURE_FAILED: 'Payment failed', VOID_FAILED: 'Hold release unconfirmed',
        REFUND_PENDING: 'Refund pending', PARTIALLY_REFUNDED: 'Partially refunded',
        INTACT: 'Record intact', UNKNOWN: 'Unknown', NEW_PROVIDER_STATE: 'New provider state',
    };
    for (const [value, label] of Object.entries(expected)) assert.equal(display.state(value), label);
    assert.equal(display.state('NOT_FUNDED / CAPTURE_SHORTFALL'), 'Not funded / Completed payments below minimum');
    assert.equal(display.state(null), 'Unknown');
});

test('short IDs keep small values and never mutate the full value', () => {
    const full = '12345678-1234-5678-1234-567812345678';
    assert.equal(display.shortId(full), '12345678…');
    assert.equal(full, '12345678-1234-5678-1234-567812345678');
    assert.equal(display.shortId('cap-1'), 'cap-1');
    assert.equal(display.shortId(null), 'Unknown');
});

test('server and polling state maps have exactly the same labels', () => {
    const kotlin = fs.readFileSync('src/main/kotlin/app/web/layout/HumanDisplay.kt', 'utf8');
    const javascript = fs.readFileSync('src/main/resources/static/js/human-display.js', 'utf8');
    const server = Object.fromEntries([...kotlin.matchAll(/"([A-Z_]+)" to "([^"]+)"/g)].map(match => [match[1], match[2]]));
    const client = Object.fromEntries([...javascript.matchAll(/([A-Z_]+): '([^']+)'/g)].map(match => [match[1], match[2]]));
    assert.deepEqual(client, server);
    for (const [state, label] of Object.entries(server)) assert.equal(display.state(state), label);
});
