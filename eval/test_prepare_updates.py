"""发布目录必须保留其他渠道，并在失败时维持原来的可用目录。"""
import hashlib, importlib.util, json, tempfile
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('prepare_updates',ROOT/'android/prepare_updates.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

def main():
    with tempfile.TemporaryDirectory(dir=ROOT,prefix='.feed-test-') as tmp:
        root=Path(tmp);apk=root/'preview.apk';apk.write_bytes(b'fixture-apk')
        digest=hashlib.sha256(apk.read_bytes()).hexdigest()
        metadata={'versionCode':30,'versionName':'0.30','sha256':digest,'size':apk.stat().st_size,'certificateSha256':'a'*64,'url':'https://example.test/'+digest}
        prod={'versionCode':26,'sha256':'b'*64,'certificateSha256':'c'*64}
        existing=root/'latest.json';original={'schema':1,'packages':{'com.nopalou':prod}};existing.write_text(json.dumps(original))
        with patch.object(module,'inspect',return_value=('com.nopalou.preview',metadata.copy())):
            feed=module.prepare([apk],root,root,existing)
        assert feed['packages']['com.nopalou']==prod
        destination=root/'apks'/digest/module.NAMES['com.nopalou.preview'];assert destination.read_bytes()==apk.read_bytes()
        published=existing.read_bytes()
        for change in ({'versionCode':29},{'certificateSha256':'d'*64},{'sha256':'e'*64}):
            rejected=False
            with patch.object(module,'inspect',return_value=('com.nopalou.preview',metadata|change)):
                try:module.prepare([apk],root,root,existing)
                except ValueError:rejected=True
            assert rejected,change
            assert existing.read_bytes()==published
        with patch.object(module,'inspect',side_effect=[('com.nopalou.preview',metadata|{'versionCode':31}),ValueError('second APK invalid')]):
            try:module.prepare([apk,apk],root,root,existing)
            except ValueError:pass
            else:raise AssertionError('partially valid release accepted')
        assert existing.read_bytes()==published
        print('PASS 保留渠道、内容校验、拒绝降级、签名不符、版本重写及部分失败不改变目录')
if __name__=='__main__':main()
