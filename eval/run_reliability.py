"""每个场景重置引擎；报告分母和失败，不把构造场景称作真实用户准确率。"""
from __future__ import annotations
import argparse, base64, hashlib, json, subprocess, tempfile, sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
ROOT=Path(__file__).resolve().parents[1]
def b64(value): return base64.b64encode(value.encode()).decode()
def run(cases,ref):
    with tempfile.TemporaryDirectory(prefix='.reliability-',dir=ROOT) as tmp:
        folder=Path(tmp);app=ROOT/'android/app/src/com/nopalou'
        if ref:
            app=folder/'sources';app.mkdir()
            for name in ('Engine','ChangeDiff','StateFormat','TaskChanges'):
                r=subprocess.run(['git','show',f'{ref}:android/app/src/com/nopalou/{name}.java'],cwd=ROOT,capture_output=True)
                if r.returncode==0:(app/(name+'.java')).write_bytes(r.stdout)
        subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*map(str,[app/(n+'.java') for n in ('Engine','ChangeDiff','StateFormat','TaskChanges') if (app/(n+'.java')).exists()]),str(ROOT/'eval/ReliabilityReplay.java')],check=True)
        lines=[]
        for case in cases:
            lines.append('CASE\t'+case['id'])
            for i,m in enumerate(case['messages']):
                ts=int(datetime.fromisoformat(m['at']).replace(tzinfo=ZoneInfo('Asia/Shanghai')).timestamp()*1000)
                lines.append('\t'.join(['MSG',str(i),str(ts),b64(m.get('group','isolated:'+case['id'])),b64(m.get('sender','王老师')),b64(m['text']),'1' if m.get('time_uncertain') else '0']))
        r=subprocess.run(['java','-Duser.timezone=Asia/Shanghai','-cp',tmp,'com.nopalou.ReliabilityReplay',str(ROOT/'android/app/assets/model.tsv')],input='\n'.join(lines)+'\n',capture_output=True,encoding='ascii',check=True)
        states={};current=None
        for line in r.stdout.splitlines():
            fields=line.split('\t')
            if fields[0]=='CASE':current=fields[1];states[current]=[]
            else:states[current].append({'title':base64.b64decode(fields[1]).decode(),'deadline':int(fields[2]),'needs_confirm':fields[3]=='true','group':base64.b64decode(fields[4]).decode()})
        results=[]
        for case in cases:
            cards=states[case['id']];expect=case['expect'];errors=[]
            if len(cards)!=expect['count']:errors.append(f"count {len(cards)} != {expect['count']}")
            if 'pending' in expect and sum(c['needs_confirm'] for c in cards)!=expect['pending']:errors.append('pending count mismatch')
            if 'deadline' in expect:
                expected=int(datetime.fromisoformat(expect['deadline']).replace(tzinfo=ZoneInfo('Asia/Shanghai')).timestamp()*1000)
                if not cards or cards[0]['deadline']!=expected:errors.append('deadline mismatch')
            results.append({'id':case['id'],'family':case['family'],'passed':not errors,'errors':errors,'cards':cards})
        return {'source':ref or 'working tree','cases':len(results),'passed':sum(r['passed'] for r in results),'failures':[r for r in results if not r['passed']],'details':results}
def main():
    p=argparse.ArgumentParser();p.add_argument('--baseline-ref');p.add_argument('--out',type=Path,required=True);args=p.parse_args()
    dataset=ROOT/'eval/reliability_cases.jsonl';raw=dataset.read_bytes();cases=[json.loads(line) for line in raw.decode().splitlines() if line.strip()]
    report={'dataset_sha256':hashlib.sha256(raw).hexdigest(),'provenance':'2026-11-09 新构造的独立会话场景，未用于训练；同一开发代理编写与评分，不是真实用户或盲测数据。每个场景完全重置群上下文。','candidate':run(cases,None)}
    if args.baseline_ref:report['baseline']=run(cases,args.baseline_ref)
    args.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:{'passed':v['passed'],'cases':v['cases'],'failed':[f['id'] for f in v['failures']]} for k,v in report.items() if isinstance(v,dict)},ensure_ascii=False))
    return int(bool(report['candidate']['failures']))
if __name__=='__main__':sys.exit(main())
