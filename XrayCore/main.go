package XrayCore

import (
	"github.com/xtls/xray-core/infra/conf"
	"github.com/xtls/libxray/nodep"
	"github.com/xtls/libxray/share"
	"XrayCore/lib"
)

func Test(dir string, config string) string {
	err := lib.Test(dir, config)
	return lib.WrapError(err)
}

func Start(dir string, config string) string {
	err := lib.Start(dir, config)
	return lib.WrapError(err)
}

func Stop() string {
	err := lib.Stop()
	return lib.WrapError(err)
}

// IsRunning returns whether the core is currently started.
// Exposed for gomobile so Kotlin/Java can directly query the state.
func IsRunning() bool {
	return lib.IsRunning()
}

func Version() string {
	return lib.Version()
}

func Json(link string) string {
	var response nodep.CallResponse[*conf.Config]
	xrayJson, err := share.ConvertShareLinksToXrayJson(link)
	return response.EncodeToBase64(xrayJson, err)
}
