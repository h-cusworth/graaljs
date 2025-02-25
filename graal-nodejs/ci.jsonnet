local common = import '../common.jsonnet';
local ci = import '../ci.jsonnet';

{
  local graalNodeJs = ci.jobtemplate + {
    cd:: 'graal-nodejs',
  },

  local artifact = {
    artifact:: 'nodejs',
  },

  local ce = ci.ce,
  local ee = ci.ee,

  local vm_env = {
    suiteimports+:: ['vm', 'substratevm', 'tools'],
    nativeimages+:: ['lib:graal-nodejs', 'lib:jvmcicompiler'], // 'js'
  },

  local gateTags(tags) = common.gateTags + {
    environment+: {
      TAGS: tags,
    },
  },

  local build = {
    run+: [
      ['[', '${ARTIFACT_NAME}', ']', '||', 'mx', 'build', '--force-javac'], // build only if no artifact is being used
    ],
  },

  local gateSubstrateVmSmokeTest = {
    run+: [
      ['mx', '--env', 'svm', 'build'],
      ['set-export', 'GRAALVM_HOME', ['mx', '--quiet', '--env', 'svm', 'graalvm-home']],
      ['${GRAALVM_HOME}/bin/node', '-e', 'console.log(\'Hello, World!\')'],
      ['${GRAALVM_HOME}/bin/npm', '--version'],
    ],
    timelimit: '30:00',
  },

  local gateVmSmokeTest = build + {
    run+: [
      ['set-export', 'GRAALVM_HOME', ['mx', '--quiet', 'graalvm-home']],
      ['${GRAALVM_HOME}/bin/node', '-e', 'console.log(\'Hello, World!\')'],
      ['${GRAALVM_HOME}/bin/npm', '--version'],
    ],
    timelimit: '30:00',
  },

  local gateCoverage = {
    coverage_gate_args:: ['--jacoco-omit-excluded', '--jacoco-relativize-paths', '--jacoco-omit-src-gen', '--jacoco-format', 'lcov', '--jacocout', 'coverage'],
    run+: [
      ['mx', '--dynamicimports', '/tools,/wasm', 'gate', '-B=--force-deprecation-as-warning', '-B=-A-J-Dtruffle.dsl.SuppressWarnings=truffle', '--strict-mode', '--tags', 'build,coverage'] + self.coverage_gate_args,
    ],
    teardown+: [
      ['mx', 'sversions', '--print-repositories', '--json', '|', 'coverage-uploader.py', '--associated-repos', '-'],
    ],
    timelimit: '1:00:00',
  },

  local checkoutNodeJsBenchmarks = {
    setup+: [
      ['git', 'clone', '--depth', '1', ['mx', 'urlrewrite', 'https://github.com/graalvm/nodejs-benchmarks.git'], '../nodejs-benchmarks'],
    ],
  },

  local auxEngineCache = {
    graalvmtests:: '../../graalvm-tests',
    run+: [
      ['python', self.graalvmtests + '/test.py', '-g', ['mx', '--quiet', 'graalvm-home'], '--print-revisions', '--keep-on-error', 'test/graal/aux-engine-cache'],
    ],
    timelimit: '1:00:00',
  },

  local testNode(suite, part='-r0,1', max_heap='8G') = {
    environment+: {
      SUITE: suite,
      PART: part,
      MAX_HEAP: max_heap,
    },
    run+: [
      ['mx', 'graalvm-show'],
      ['mx', 'testnode', '-Xmx${MAX_HEAP}', '${SUITE}', '${PART}'],
    ],
    timelimit: '1:15:00',
  },

  local buildAddons = build + {
    run+: [
      ['mx', 'makeinnodeenv', 'build-addons'],
    ],
    timelimit: '30:00',
  },

  local buildNodeAPI = build + {
    run+: [
      ['mx', 'makeinnodeenv', 'build-node-api-tests'],
    ],
    timelimit: '30:00',
  },

  local buildJSNativeAPI = build + {
    run+: [
      ['mx', 'makeinnodeenv', 'build-js-native-api-tests'],
    ],
    timelimit: '30:00',
  },

  local parallelHttp2 = 'parallel/test-http2-.*',
  local parallelNoHttp2 = 'parallel/(?!test-http2-).*',

  builds: [
    // gates
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64                                + common.gateStyleFullBuild                                                            + {name: 'nodejs-gate-style-fullbuild-jdk17-linux-amd64'},
    graalNodeJs + common.jdk20 + common.gate      + common.linux_amd64                                + common.gateStyleFullBuild                                                            + {name: 'nodejs-gate-style-fullbuild-jdk20-linux-amd64'},

    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64                                + gateTags('all')          + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-gate-jdk17-linux-amd64'},
    graalNodeJs + common.jdk20 + common.gate      + common.linux_amd64                                + gateTags('all')          + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-gate-jdk20-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_aarch64                              + gateTags('all')          + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-gate-jdk17-linux-aarch64'},
    graalNodeJs + common.jdk17 + common.gate      + common.darwin_aarch64                             + gateTags('all')          + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-gate-jdk17-darwin-aarch64'},
    graalNodeJs + common.jdk17 + common.gate      + common.windows_amd64                              + gateTags('windows')      + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-gate-jdk17-windows-amd64'},

    graalNodeJs + common.jdk17 + common.gate      + common.darwin_aarch64                             + gateSubstrateVmSmokeTest                                                             + {name: 'nodejs-gate-substratevm-ce-jdk17-darwin-aarch64'},
    graalNodeJs + common.jdk17 + common.gate      + common.windows_amd64                              + gateSubstrateVmSmokeTest                                                             + {name: 'nodejs-gate-substratevm-ce-jdk17-windows-amd64'},

    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env                    + gateVmSmokeTest                                                    + artifact   + ce + {name: 'nodejs-gate-substratevm-ce-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env                    + gateVmSmokeTest                                                    + artifact   + ee + {name: 'nodejs-gate-substratevm-ee-jdk17-linux-amd64'},

    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64             + buildAddons      + testNode('addons',        part='-r0,1', max_heap='8G')                               + {name: 'nodejs-gate-addons-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64             + buildNodeAPI     + testNode('node-api',      part='-r0,1', max_heap='8G')                               + {name: 'nodejs-gate-node-api-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64             + buildJSNativeAPI + testNode('js-native-api', part='-r0,1', max_heap='8G')                               + {name: 'nodejs-gate-js-native-api-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode('async-hooks',   part='-r0,1', max_heap='8G')             + artifact        + {name: 'nodejs-gate-async-hooks-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode('es-module',     part='-r0,1', max_heap='8G')             + artifact        + {name: 'nodejs-gate-es-module-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode('sequential',    part='-r0,1', max_heap='8G')             + artifact        + {name: 'nodejs-gate-sequential-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode(parallelNoHttp2, part='-r0,5', max_heap='8G')             + artifact        + {name: 'nodejs-gate-parallel-1-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode(parallelNoHttp2, part='-r1,5', max_heap='8G')             + artifact        + {name: 'nodejs-gate-parallel-2-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode(parallelNoHttp2, part='-r2,5', max_heap='8G')             + artifact        + {name: 'nodejs-gate-parallel-3-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode(parallelNoHttp2, part='-r3,5', max_heap='8G')             + artifact        + {name: 'nodejs-gate-parallel-4-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + testNode(parallelNoHttp2, part='-r4,5', max_heap='8G')             + artifact        + {name: 'nodejs-gate-parallel-5-jdk17-linux-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.linux_amd64    + vm_env + build            + auxEngineCache                                                     + artifact   + ee + {name: 'nodejs-gate-aux-engine-cache-jdk17-linux-amd64'},

    graalNodeJs + common.jdk17 + common.gate      + common.windows_amd64           + build            + testNode('async-hooks',   part='-r0,1', max_heap='8G')                               + {name: 'nodejs-gate-async-hooks-jdk17-windows-amd64'},
    graalNodeJs + common.jdk17 + common.gate      + common.windows_amd64           + build            + testNode('es-module',     part='-r0,1', max_heap='8G')                               + {name: 'nodejs-gate-es-module-jdk17-windows-amd64'},
    # We run the `sequential` tests with a smaller heap because `test/sequential/test-child-process-pass-fd.js` starts 80 child processes.
    graalNodeJs + common.jdk17 + common.gate      + common.windows_amd64           + build            + testNode('sequential',    part='-r0,1', max_heap='512M')                             + {name: 'nodejs-gate-sequential-jdk17-windows-amd64'},

    // post-merges
    graalNodeJs + common.jdk17 + common.postMerge + common.linux_amd64    + vm_env + build            + testNode(parallelHttp2,   part='-r0,1', max_heap='8G')                               + {name: 'nodejs-postmerge-parallel-http2-jdk17-linux-amd64'},

    graalNodeJs + common.jdk17 + common.postMerge + common.darwin_amd64                               + gateTags('all')          + {dynamicimports+:: ['/wasm']}                             + {name: 'nodejs-postmerge-jdk17-darwin-amd64', timelimit: '55:00'},
    graalNodeJs + common.jdk17 + common.postMerge + common.darwin_amd64                               + gateSubstrateVmSmokeTest                                                             + {name: 'nodejs-postmerge-substratevm-ce-jdk17-darwin-amd64', timelimit: '55:00'},

    // weekly
    graalNodeJs + common.jdk17 + common.weekly    + common.linux_amd64                                + gateCoverage                                                                         + {name: 'weekly-nodejs-coverage-jdk17-linux-amd64'},
  ],
}
