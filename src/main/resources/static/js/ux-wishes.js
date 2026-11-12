(() => {
    const form = document.getElementById('wish-selection');
    if (!form) return;
    const wishes = [...form.querySelectorAll('input[name="wishIds"]')];
    const selectAll = document.getElementById('wish-select-all');
    const count = document.getElementById('wish-selection-count');
    const buttons = [...form.querySelectorAll('button[name="hidden"]')];
    selectAll.closest('label').hidden = false;
    const update = () => {
        const selected = wishes.filter(wish => wish.checked).length;
        count.textContent = `${selected} of ${wishes.length} selected`;
        selectAll.checked = selected === wishes.length;
        selectAll.indeterminate = selected > 0 && selected < wishes.length;
        buttons.forEach(button => { button.disabled = selected === 0; });
    };
    selectAll.addEventListener('change', () => {
        wishes.forEach(wish => { wish.checked = selectAll.checked; });
        update();
    });
    form.addEventListener('change', update);
    window.addEventListener('pageshow', update);
    update();
})();
