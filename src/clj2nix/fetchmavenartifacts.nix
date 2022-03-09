{ lib, fetchurl, stdenv, linkFarm }:
args@
{ # Example: "org.apache.httpcomponents"
  groupId
, # Example: "httpclient"
  artifactId
, # Example: "4.3.6"
  version
, # Example: "jdk11"
  classifier ? null
, # List of maven repositories from where to fetch the artifact.
  # Example: [ http://oss.sonatype.org/content/repositories/public ].
  repos ? [
    "https://repo1.maven.org/maven2/"
    "https://repo.clojars.org/"
  ]
, sha256s
}:
let
  name_ =
    lib.concatStrings [
      (lib.replaceChars ["."] ["_"] groupId) "_"
      (lib.replaceChars ["."] ["_"] artifactId) "-"
      version
    ];

  mkUrl = ext: repoUrl:
    lib.concatStringsSep "/" [
      (lib.removeSuffix "/" repoUrl)
      (lib.replaceChars ["."] ["/"] groupId)
      artifactId
      version
      "${artifactId}-${version}${lib.optionalString (!isNull classifier) "-${classifier}"}.${ext}"
    ];

  srcs = lib.mapAttrs (ext: sha256:
    fetchurl {
      urls = map (mkUrl ext) repos;
      name = "${name_}.${ext}";
      inherit sha256;
    }) sha256s;
in
# linkFarm name_ (map (ext: {
#   name = "${artifactId}-${version}.${ext}";
#   path = fetchurl {
#     name = "${name_}.${ext}";
#     urls = map (mkUrl ext) repos;
#     sha256 = sha256s.${ext};
#   };
# }) ["jar" "pom"])
  stdenv.mkDerivation {
    name = name_;
    buildCommand = ''
      mkdir -p $out
    '' +
    lib.concatStringsSep "\n" (lib.mapAttrsToList (ext: file:
      "ln -s ${file} $out/${artifactId}-${version}.${ext}") srcs);

    passthru = {
      inherit groupId artifactId version;
      repoType = "maven";
    } // srcs;
  }
