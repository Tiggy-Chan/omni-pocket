apply_mnn_patch() {
    local mnn="$1"
    local patch="$2"
    if [[ ! -f "$patch" ]]; then
        printf 'Missing MNN patch: %s\n' "$patch" >&2
        return 1
    fi
    if git -C "$mnn" apply --check "$patch" >/dev/null 2>&1; then
        git -C "$mnn" apply "$patch"
    elif git -C "$mnn" apply --reverse --check "$patch" >/dev/null 2>&1; then
        printf 'MNN patch already applied: %s\n' "${patch##*/}"
    else
        printf 'MNN checkout is incompatible with patch: %s\n' "$patch" >&2
        return 1
    fi
}
