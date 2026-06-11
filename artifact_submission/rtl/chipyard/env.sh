# Artifact Chipyard environment helper.
# Source this file from the restored artifact Chipyard root.

export CY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! type conda >/dev/null 2>&1; then
    if [ -f "$HOME/miniforge3/etc/profile.d/conda.sh" ]; then
        . "$HOME/miniforge3/etc/profile.d/conda.sh"
    elif [ -f "$HOME/miniconda3/etc/profile.d/conda.sh" ]; then
        . "$HOME/miniconda3/etc/profile.d/conda.sh"
    else
        echo "::ERROR:: conda is required before sourcing env.sh"
        return 1
    fi
fi

source "$(conda info --base)/etc/profile.d/conda.sh"
if [ -d "$CY_DIR/.conda-env" ]; then
    conda activate "$CY_DIR/.conda-env"
fi

if [ -f "$CY_DIR/scripts/fix-open-files.sh" ]; then
    source "$CY_DIR/scripts/fix-open-files.sh"
fi
