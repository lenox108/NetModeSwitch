# Load from ~/.zshrc: source ~/Documents/NetworkSwitcher/NetModeSwitch/tools/gitpull.zsh

gitpull() {
  emulate -L zsh
  local repo rc=0 cloud
  local -a repos=("$@")
  if (( ${#repos} == 0 )); then
    repos=("$HOME/Documents/Cursor01/ForPDA-master" "$HOME/Documents/NetworkSwitcher/NetModeSwitch")
  fi
  for repo in "${repos[@]}"; do
    cloud=0
    [[ ${repo:A:t} == NetModeSwitch ]] && cloud=1
    _gitpull_main "$repo" "$cloud" || rc=1
  done
  return $rc
}

_gitpull_main() {
  emulate -L zsh
  local repo=${1:A} cloud=${2:-0} branch state_file git_dir before after saved_stash='' rc=0
  print -r -- "📦 ${repo:t}"
  git -C "$repo" rev-parse --show-toplevel >/dev/null 2>&1 || {
    print -r -- "⚠️  Не найден Git-репозиторий: $repo"
    return 1
  }
  branch=$(git -C "$repo" symbolic-ref --short HEAD 2>/dev/null)
  if [[ $branch != main ]]; then
    print -r -- "⚠️  Открыта ветка ${branch:-(без ветки)}, нужна main: $repo"
    return 1
  fi
  git_dir=$(git -C "$repo" rev-parse --absolute-git-dir) || return 1
  for state_file in rebase-merge rebase-apply MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD sequencer; do
    if [[ -e $git_dir/$state_file ]]; then
      print -r -- "⚠️  Сначала заверши текущую Git-операцию в $repo ($state_file)."
      return 1
    fi
  done
  before=$(git -C "$repo" rev-parse HEAD) || return 1
  if [[ -n $(git -C "$repo" status --porcelain) ]]; then
    # Include new files too: upstream may introduce a file with the same name.
    git -C "$repo" stash push --include-untracked -m "gitpull: ${repo:t} $(date '+%Y-%m-%d %H:%M:%S')" || return 1
    saved_stash=$(git -C "$repo" rev-parse refs/stash) || return 1
  fi

  # An explicit strategy also handles local commits from earlier cloud cherry-picks.
  # Our stash preserves staged and untracked files; disable Git's second autostash.
  git -C "$repo" -c rebase.autoStash=false pull --rebase origin main || rc=$?
  if (( rc != 0 )); then
    if [[ -d $git_dir/rebase-merge || -d $git_dir/rebase-apply ]]; then
      if ! git -C "$repo" rebase --abort; then
        print -r -- "⚠️  Не удалось отменить rebase. Локальные файлы сохранены в stash: $saved_stash"
        return 1
      fi
    fi
    print -r -- "⚠️  Pull не завершён; локальные коммиты сохранены."
  elif (( cloud )); then
    _gitpull_cloud "$repo" || rc=$?
  fi

  if [[ -n $saved_stash ]]; then
    if git -C "$repo" stash apply --index "$saved_stash"; then
      # Never remove a stash created by another process in the meantime.
      if [[ $(git -C "$repo" rev-parse refs/stash) == $saved_stash ]]; then
        git -C "$repo" stash drop 'stash@{0}' || return 1
      else
        print -r -- "ℹ️  Файлы восстановлены; резервный stash оставлен: $saved_stash"
      fi
    else
      print -r -- "⚠️  Конфликт восстановления локальных файлов в $repo. Stash сохранён: $saved_stash"
      return 1
    fi
  fi
  (( rc == 0 )) || return $rc
  after=$(git -C "$repo" rev-parse HEAD)
  if [[ $before == $after ]]; then
    print -r -- "✅ Уже актуально. Локальные файлы сохранены."
  else
    print -r -- "✅ Обновлено. Локальные файлы сохранены:"
    git -C "$repo" log --oneline --no-merges "$before..$after"
  fi
}

# Legacy Claude sessions use ccr-* branches. Codex changes merged into main arrive
# through pull; do not automatically apply unrelated or unfinished codex/* branches.
_gitpull_cloud() {
  emulate -L zsh
  local repo=$1 branch commit patch_line picked=0
  local branches patches
  git -C "$repo" fetch --prune origin || return $?
  branches=$(git -C "$repo" for-each-ref --format='%(refname:short)' 'refs/remotes/origin/ccr-*') || return 1
  for branch in ${(f)branches}; do
    [[ -n $branch ]] || continue
    patches=$(git -C "$repo" cherry main "$branch") || return 1
    for patch_line in ${(f)patches}; do
      [[ $patch_line == '+ '* ]] || continue
      commit=${patch_line#'+ '}
      if ! git -C "$repo" cherry-pick "$commit"; then
        git -C "$repo" cherry-pick --abort || return 1
        print -r -- "⚠️  Не удалось перенести $commit из $branch. Этот перенос отменён."
        return 1
      fi
      (( ++picked ))
    done
  done
  print -r -- "☁️  Из облачных веток перенесено коммитов: $picked."
}
