import csv
rows = [r for r in csv.DictReader(open('/tmp/perf-results.csv')) if r['p99'] != 'NA']
if rows:
    def med(k):
        vals = sorted(int(r[k]) for r in rows)
        return vals[len(vals) // 2]
    def medf(k):
        vals = sorted(float(r[k]) for r in rows)
        return vals[len(vals) // 2]
    print("MEDIAN(%d valid trips): frames=%d janky=%.2f%% legacy=%.2f%% p90=%dms p95=%dms p99=%dms" % (
        len(rows), med('frames'), medf('janky_pct'), medf('legacy_pct'),
        med('p90'), med('p95'), med('p99')))
else:
    print("NO VALID TRIPS")
