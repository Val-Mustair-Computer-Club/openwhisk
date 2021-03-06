#
# Copyright 2015-2016 IBM Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import os,sys
from subprocess import check_output

#Settings
COMPILE_PREFIX = "/usr/bin/swiftc -module-name Action "
LINKER_PREFIX = "/usr/bin/swiftc -Xlinker '-rpath=$ORIGIN' '-L/swift3Action/spm-build/.build/release' -o '/swift3Action/spm-build/.build/release/Action'\
"
GENERATED_BUILD_SCRIPT = "/swift3Action/spm-build/swiftbuildandlink.sh"
SPM_DIRECTORY = "/swift3Action/spm-build"
BUILD_COMMAND = ["swift","build","-v","-c","release"]

## Build Swift package and capture step trace
print "Building action"
out = check_output(BUILD_COMMAND, cwd=SPM_DIRECTORY)
print "action built. Decoding compile and link commands"

## Look for compile and link commands in step trace
compileCommand = None
linkCommand = None

buildInstructions = out.decode("utf-8").splitlines()

for instruction in buildInstructions:
    if instruction.startswith(COMPILE_PREFIX):
        compileCommand = instruction

        ## add flag to quiet warnings
        compileCommand += " -suppress-warnings"

    elif instruction.startswith(LINKER_PREFIX):
        linkCommand = instruction

## if found, create build script, otherwise exit with error
if compileCommand != None and linkCommand != None:
    print "Generated OpenWhisk Compile command: %s" % compileCommand
    print "========="
    print "Generated OpenWhisk Link command: %s" % linkCommand

    with open(GENERATED_BUILD_SCRIPT, "a") as buildScript:
      buildScript.write("#!/bin/bash\n")
      buildScript.write("echo \"Compiling\"\n")
      buildScript.write("%s\n" % compileCommand)
      buildScript.write("swiftStatus=$?\n")
      buildScript.write("echo swiftc status is $swiftStatus\n")
      buildScript.write("if [[ \"$swiftStatus\" -eq \"0\" ]]; then\n")
      buildScript.write("echo \"Linking\"\n")
      buildScript.write("%s\n" % linkCommand)
      buildScript.write("else\n")
      buildScript.write(">2& echo \"Action did not compile\"\n")
      buildScript.write("exit 1\n")
      buildScript.write("fi")

    os.chmod(GENERATED_BUILD_SCRIPT, 0777)
    sys.exit(0)
else:
    print("Cannot generate build script: compile or link command not found")
    sys.exit(1)


