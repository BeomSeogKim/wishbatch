(function () {
    'use strict';

    const labels = {
        DRAFT: 'Draft', OPEN: 'Open', AWAITING_CONFIRMATION: 'Awaiting maker confirmation',
        SETTLING: 'Settlement in progress', FUNDED: 'Funded',
        NOT_FUNDED: 'Not funded', THRESHOLD_NOT_MET: 'Minimum not met',
        CANCELLED_BY_MAKER: 'Cancelled by maker', CONFIRMATION_EXPIRED: 'Confirmation expired',
        CAPTURE_SHORTFALL: 'Completed payments below minimum', CREATED: 'Created',
        AUTHORIZED: 'Hold placed', ABANDONED: 'Approval abandoned', CAPTURED: 'Paid',
        CAPTURE_PENDING: 'Payment pending', CAPTURE_FAILED: 'Payment failed',
        VOIDED: 'Hold released', VOID_FAILED: 'Hold release unconfirmed',
        REFUNDED: 'Refunded', REFUND_PENDING: 'Refund pending', REFUND_FAILED: 'Refund failed',
        PARTIALLY_REFUNDED: 'Partially refunded', UNKNOWN: 'Unknown', INTACT: 'Record intact',
        MATCH: 'Match', MISMATCH: 'Mismatch', COMPLETED: 'Completed',
        COMPATIBLE: 'Compatible', CONDITIONAL: 'Depends', INCOMPATIBLE: 'Not served', NOT_A_REQUEST: 'Not a request',
    };
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const pad = value => String(value).padStart(2, '0');

    function time(value) {
        if (value == null || value === '') return 'Not available';
        const date = new Date(value);
        if (!Number.isFinite(date.getTime())) return 'Not available';
        return date.getUTCDate() + ' ' + months[date.getUTCMonth()] + ' ' + date.getUTCFullYear() + ', ' +
            pad(date.getUTCHours()) + ':' + pad(date.getUTCMinutes()) + ' UTC';
    }

    function state(value) {
        if (value == null) return 'Unknown';
        const text = String(value);
        if (Object.hasOwn(labels, text)) return labels[text];
        const parts = text.split(/ \/ | -> /);
        if (parts.length > 1) return parts.map(state).join(text.includes(' -> ') ? ' → ' : ' / ');
        return text.toLowerCase().replace(/_/g, ' ').replace(/^./, letter => letter.toUpperCase());
    }

    function shortId(value) {
        if (value == null) return 'Unknown';
        const text = String(value);
        return text.length > 12 ? text.slice(0, 8) + '…' : text;
    }

    function money(amount, currency) {
        if (typeof amount === 'number' && !Number.isSafeInteger(amount)) throw new TypeError('Exact minor units required');
        if (!['string', 'bigint', 'number'].includes(typeof amount) || !/^-?\d+$/.test(String(amount))) {
            throw new TypeError('Exact minor units required');
        }
        const digits = new Intl.NumberFormat('en-US', {style: 'currency', currency}).resolvedOptions().maximumFractionDigits;
        const units = BigInt(amount);
        const absolute = (units < 0n ? -units : units).toString().padStart(digits + 1, '0');
        const decimal = digits ? absolute.slice(0, -digits) + '.' + absolute.slice(-digits) : absolute;
        return currency + ' ' + (units < 0n ? '-' : '') + decimal;
    }

    window.WishbatchDisplay = Object.freeze({time, state, shortId, money});
})();
