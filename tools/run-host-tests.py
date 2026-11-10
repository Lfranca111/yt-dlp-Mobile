import pathlib,os,subprocess
base=pathlib.Path(__file__).resolve().parents[1]; tool=base/'build/host-validation';tool.mkdir(parents=True,exist_ok=True);app=base/'app'
java_home=pathlib.Path(os.environ['JAVA_HOME']);sdk=pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
if not (sdk/'platforms/android-35/android.jar').is_file(): raise SystemExit('Set ANDROID_HOME to the Android SDK containing API 35')
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
jars=list(cache.rglob('*.jar'));json=[p for p in jars if '/org.json/json/' in str(p)];kotlin=[p for p in jars if 'kotlin-stdlib' in str(p)]
classes=[app/'src/test/resources',app/'build/tmp/kotlin-classes/debug',app/'build/intermediates/javac/debug/compileDebugJavaWithJavac/classes',app/'build/tmp/kotlin-classes/debugUnitTest']
cp=os.pathsep.join(map(str,json+classes+kotlin+jars+[sdk/'platforms/android-35/android.jar']))
out=tool/'host-test-classes';out.mkdir(exist_ok=True)
env=os.environ.copy();env['JAVA_HOME']=str(java_home)
java=[str(java_home/'bin/java')]
files=list((app/'src/test/java').rglob('*.java'))
r=subprocess.run(java+['--add-modules','jdk.compiler,jdk.httpserver','-m','jdk.compiler/com.sun.tools.javac.Main','-cp',cp,'-d',str(out)]+[str(p) for p in files],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env)
if r.returncode: print(r.stdout);raise SystemExit(r.returncode)
names=['com.linfranca.ytdlpmobile.'+p.stem for p in (app/'src/test/java/com/linfranca/ytdlpmobile').iterdir() if p.suffix in ['.java','.kt'] and ((out/('com/linfranca/ytdlpmobile/'+p.stem+'.class')).exists() or (classes[3]/('com/linfranca/ytdlpmobile/'+p.stem+'.class')).exists())]
r=subprocess.run(java+['--add-modules','jdk.httpserver','-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore']+names,cwd=app,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(tool/'tests.log').write_text(r.stdout);print(r.stdout);raise SystemExit(r.returncode)
