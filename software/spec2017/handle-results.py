#!/usr/bin/env python3
import pathlib
import sys
import pandas as pd
import argparse

# All time measurements are in seconds

# Reference times taken from https://www.spec.org/cpu2017/results/res2017q2/cpu2017-20161026-00004.html
specSpeedReference = pd.DataFrame.from_dict({
    "600.perlbench_s" : 1774,
    "602.gcc_s" : 3976,
    "605.mcf_s" : 4721,
    "620.omnetpp_s" : 1630,
    "623.xalancbmk_s" : 1417,
    "631.deepsjeng_s" : 1432,
    "641.leela_s" : 1703,
    "648.exchange2_s" : 2939
    }, orient='index', columns=['RealTime'])

# This is taken from agfi-0fa2162c2c1a475d4
# (firesim-rocket-quadcore-no-nic-l2-llc4mb-ddr3 circa oct 13 2020)
# specSpeedTest = pd.DataFrame.from_dict({
#     "600.perlbench_s" : 59.87,
#     "602.gcc_s" : 0.04,
#     "605.mcf_s" : 32.12,
#     "620.omnetpp_s" : 10.15,
#     "623.xalancbmk_s" : 0.31,
#     "631.deepsjeng_s" : 31.40,
#     "641.leela_s" : 12.17,
#     "648.exchange2_s" : 31.59
#     }, orient='index', columns=['RealTime'])

# Pieced together from the spec repo: spec2017/benchspec/CPU/*/data/test/reftime
specSpeedTest = pd.DataFrame.from_dict({
    "600.perlbench_s" : 74,
    "602.gcc_s" : 2,
    "605.mcf_s" : 40,
    "620.omnetpp_s" : 16,
    "623.xalancbmk_s" : 2,
    "631.deepsjeng_s" : 41,
    "641.leela_s" : 17,
    "648.exchange2_s" : 74
    }, orient='index', columns=['RealTime'])

def collect_csvs(outDir):
    csvs = sorted(outDir.glob("*/output/*.csv"))
    if not csvs:
        raise RuntimeError(
            "No result CSVs found under "
            f"{outDir}. Expected files matching */output/*.csv. "
            "This usually means the workload did not export /output from the guest."
        )
    return csvs

def handleSpeed(outDir, dataset):
    if dataset == 'test':
        baseline = specSpeedTest
    elif dataset == 'ref':
        baseline = specSpeedReference
    else:
        baselinePath = pathlib.Path(dataset)
        if not baselinePath.exists():
            raise RuntimeError("Baseline csv doesn't exist: ", dataset)
        baseline = pd.read_csv(baselinePath, index_col=0)
 
    resDF = None
    for csvFile in collect_csvs(outDir):
        if resDF is None:
            resDF = pd.read_csv(csvFile, index_col=0)
        else:
            resDF = resDF.append(pd.read_csv(csvFile, index_col=0))

    resDF['score'] = baseline['RealTime'] / resDF['RealTime']
    resDF.sort_index(inplace=True)
    return resDF


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Aggregate results from a run of a SPEC suite")
    parser.add_argument('-s', '--suite', required=True, choices=['intspeed'], help="Which suite was run.")
    parser.add_argument('-d', '--dataset', required=True, help="Which dataset was used, either test or ref. You can also specify a path to a previous output of this script to use as a baseline.")
    parser.add_argument('outputPath', type=pathlib.Path, help="Output directory to process")

    args = parser.parse_args()

    resDF = handleSpeed(args.outputPath, dataset=args.dataset)

    with open(args.outputPath / "results.csv", "w") as f:
        f.write(resDF.to_csv())

    plot = resDF['score'].plot(kind="bar", title="SPEC Score")
    plot.get_figure().savefig(args.outputPath / "results.pdf", bbox_inches = "tight")
    print("Output available in: ", args.outputPath)
