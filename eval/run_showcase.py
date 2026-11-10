"""用 Android 同一场景和引擎导出三条可核对分支，不调用 AI，不读取个人数据。"""
from __future__ import annotations
import argparse,base64,json,subprocess,tempfile
from datetime import datetime,timedelta,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ZONE=timezone(timedelta(hours=8))
def decode(value):return base64.b64decode(value).decode('utf-8')
def timestamp(value):return '待核对' if not value else datetime.fromtimestamp(value/1000,ZONE).strftime('%m-%d %H:%M')
def escape(value):return str(value).replace('|','\\|').replace('\n','<br>')
def run(classes,branch,at):
    r=subprocess.run(['java','-Duser.timezone=Asia/Shanghai','-cp',classes,'com.nopalou.ShowcaseDump',str(ROOT/'android/app/assets/model.tsv'),branch,str(at)],capture_output=True,encoding='ascii',check=True)
    steps=[]
    for line in r.stdout.splitlines():
        f=line.split('\t');tag=f[0]
        if tag=='STEP':steps.append({'step':int(f[1]),'title':decode(f[2]),'note':decode(f[3]),'summary':decode(f[4]),'messages':[],'cards':[],'removed':[]})
        elif tag=='MESSAGE':steps[-1]['messages'].append({'at':int(f[1]),'actor':decode(f[2]),'text':decode(f[3]),'user_action':f[4]=='true'})
        elif tag in ('CARD','REMOVED'):
            steps[-1]['cards' if tag=='CARD' else 'removed'].append({'title':decode(f[1]),'deadline':int(f[2]),'needs_confirm':f[3]=='true','done':f[4]=='true','trace':decode(f[5])})
        else:raise ValueError('Unknown replay output')
    if [s['step'] for s in steps]!=list(range(7)):raise ValueError('Incomplete showcase replay')
    return {'decision':branch,'steps':steps}
def markdown(report):
    out=['# outfloor 情境演示回放','','此文件由 Android 同一场景类和真实引擎回放生成。数据为构造样例，仅展示处理流程，不代表真实用户准确率。','','手机入口：更多 → 演示 → 情境演示。全程本机处理，样例与日常待办隔离；页面重建可恢复当前演示步骤。','','以下展开“将含糊变更关联到数据库实验报告”这一分支。']
    for step in report['branches'][0]['steps']:
        out += ['',f"## 步骤 {step['step']}：{step['title']}",'',step['note'],'',step['summary'],'']
        if step['messages']:
            latest=step['messages'] if step['step']==1 else step['messages'][-1:]
            for m in latest:out += [f"{timestamp(m['at'])} · {m['actor']} · {'本人操作（构造）' if m['user_action'] else '样例通知'}",'',m['text'],'']
        if step['cards'] or step['removed']:
            out+=['| 事项 | 时间 | 状态 |','|---|---|---|']
            for removed,cards in ((False,step['cards']),(True,step['removed'])):
                for card in cards:
                    state='已取消' if removed else '本人已完成' if card['done'] else '待核对' if card['needs_confirm'] else '待办'
                    out.append(f"| {escape(card['title'])} | {timestamp(card['deadline'])} | {state} |")
    out+=['','## 另两种核对方式','','选择物理实验报告时，只调整物理事项；选择忽略时，两项保留原安排。两条分支也由真实引擎完整执行，结果保存在配套 JSON。','','## 最后状态的来源和操作记录','']
    last=report['branches'][0]['steps'][-1]
    for card in last['cards']+last['removed']:
        out += ['### '+card['title'],'',card['trace'],'']
    return '\n'.join(out)+'\n'
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,required=True);parser.add_argument('--markdown',type=Path);parser.add_argument('--at',default=None,help='北京时间 ISO 日期时刻，可用于固定演示起点');args=parser.parse_args()
    now=datetime.fromisoformat(args.at).replace(tzinfo=ZONE) if args.at else datetime.now(ZONE)
    at=int(now.timestamp()*1000);app=ROOT/'android/app/src/com/nopalou'
    with tempfile.TemporaryDirectory(prefix='.showcase-',dir=ROOT) as classes:
        sources=[app/(name+'.java') for name in ('Engine','StateFormat','TaskChanges','ChangeDiff','TaskTrace','ShowcaseScenario')]
        subprocess.run(['javac','-encoding','UTF-8','--release','11','-d',classes,*map(str,sources),str(ROOT/'eval/ShowcaseDump.java')],check=True)
        report={'schema':1,'data_kind':'constructed','scope':'同 Android 情境演示引擎，离线回放；不包含通知权限、真实云端或原生界面验证','started_at':at,'branches':[run(classes,b,at) for b in ('database','physics','ignore')]}
    args.out.parent.mkdir(parents=True,exist_ok=True);args.out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    if args.markdown:args.markdown.write_text(markdown(report),encoding='utf-8')
    print('Exported 3 constructed branches, 7 states each; no personal data or cloud calls')
if __name__=='__main__':main()
