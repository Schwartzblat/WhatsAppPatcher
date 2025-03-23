setTimeout(function () {
    Java.perform(function () {
        // const blacklist = {
        // 4377: true,
        // 6989: true,
        // 3289: true,
        // 5257: true,
        // 6743: true,
        // 1448: true,
        // 3099: true,
        // 1700: true,
        // 4137: true,
        // 982: true,
        // 4314: true,
        // 4023: true,
        // 6327: true,
        // 6539: true,
        // 3956: true,
        // };
        // let a2sp = Java.use("X.11t");
        // a2sp["A0F"].implementation = function (r5, i) {
        // 	let result = this["A0F"](r5, i);
        // 	if (blacklist[i]) {
        // 		result = false;
        // 	}
        // 	console.log(`${i}: ${result},`);
        // 	return result;
        // };
        /*let a01D = Java.use("X.01D");
        a01D["A03"].implementation = function (context) {
            console.log('A03 is called' + ', ' + 'context: ' + context);
            let ret = this.A03(context);
            console.log('A03 ret value is ' + ret);
            console.log(ret[0].toCharsString());
            return ret;
        };*/
        /*const exclude = [
            'j',
            'appUpdateDraw'
        ];
        let some_class = Java.use("com.android.billingclient.api.b");
        for (let method of some_class.class.getDeclaredMethods()) {
            const method_name = method.getName();
            if (exclude.includes(method_name)) {
                continue;
            }
            for (let overload of some_class[method_name].overloads) {
                overload.implementation = function (...args) {
                    console.log(`${method_name} is called with ${args}`);
                    let ret;
                    if (args?.length) {
                        ret = this[method_name](...args);
                    } else {
                        ret = this[method_name]();
                    }
                    console.log(`The return value of ${method_name} is ${ret}`);
                    return ret;
                }
            }
        }


        some_class["j"].implementation = function () {
            console.log(`b.j is called`);
            let result = this["j"]();
            console.log(`b.j result=${result}`);
            return true;
        };

        let b = Java.use("com.android.billingclient.api.b");
        b["L"].implementation = function (eVar, fVar) {
            console.log(`b.L is called: eVar=${eVar}, fVar=${fVar}`);
            this._n.value = false;
            let result = this["L"](eVar, fVar);
            console.log(`b.L result=${result}`);
            return result;
        };

        let zzc = Java.use("com.google.android.gms.internal.play_billing.zzc");
        zzc["zza"].implementation = function (i10, str, str2) {
            console.log(`zzc.zza is called: i10=${i10}, str=${str}, str2=${str2}`);
            let result = this["zza"](i10, str, str2);
            console.log(`zzc.zza result=${result}`);
            return 0;
        };*/

        // let w = Java.use("com.android.billingclient.api.w");
        // w["onReceive"].implementation = function (context, intent) {
        // 	console.log(`w.onReceive is called: context=${context}, intent=${intent}`);
        // 	console.log(Java.use("android.util.Log").getStackTraceString(Java.use("java.lang.Exception").$new()))
        // 	this["onReceive"](context, intent);
        // };
        // let ProxyBillingActivity = Java.use("com.android.billingclient.api.ProxyBillingActivity");
        // ProxyBillingActivity["onActivityResult"].implementation = function (i10, i11, intent) {
        // 	console.log(`ProxyBillingActivity.onActivityResult is called: i10=${i10}, i11=${i11}, intent=${intent}`);
        // 	this["onActivityResult"](i10, i11, intent);
        // };
        //
        // ProxyBillingActivity["onCreate"].implementation = function (bundle) {
        // 	console.log(`ProxyBillingActivity.onCreate is called: bundle=${bundle}`);
        // 	this["onCreate"](bundle);
        // };
        //
        //
        // let activity_result = Java.use('android.app.Activity');
        // activity_result['startIntentSenderForResult'].overload('android.content.IntentSender', 'int', 'android.content.Intent', 'int', 'int', 'int').implementation = function (...args) {
        // 	console.log(`startIntentSenderForResult is called: args=${args}`);
        // 	debugger;
        // 	this["startIntentSenderForResult"](...args);
        // };


        /*let b = Java.use("com.android.billingclient.api.b");
        b["f"].implementation = function (fVar, hVar) {
            console.log(`b.f is called: fVar=${fVar}, hVar=${hVar}`);
            console.log(`This.t == ${this._t}`);
            this._t.value = true;
            this["f"](fVar, hVar);
        };

        b["j"].implementation = function () {
            console.log(`b.j is called`);
            let result = this["j"]();
            console.log(`b.j result=${result}`);
            return true;
        };

        b["A"].implementation = function (callable, j10, runnable, handler) {
            console.log(`b.A is called: callable=${callable}, j10=${j10}, runnable=${runnable}, handler=${handler}`);
            let result = this["A"](callable, j10, runnable, handler);
            console.log(`b.A result=${result}`);
            return result;
        };*/

        function hook_all_methods(klass, callback) {
            for (let method of klass.class.getDeclaredMethods()) {
                const method_name = method.getName();
                for (let overload of klass[method_name].overloads) {
                    overload.implementation = function () {
                        return callback(this, method_name, arguments);
                    };
                }
            }
        }

        // let SettingsGoogleDrive = Java.use("com.whatsapp.backup.google.SettingsGoogleDrive");
        // let settings_google_drive_spaces = 0;
        // hook_all_methods(SettingsGoogleDrive, function (self, method_name, args) {
        //     console.log(`${' '.repeat(settings_google_drive_spaces)}SettingsGoogleDrive.${method_name} is called with ${JSON.stringify(args)}`);
        //     settings_google_drive_spaces += 2;
        //     let result = self[method_name](...args);
        //     settings_google_drive_spaces -= 2;
        //     console.log(`${' '.repeat(settings_google_drive_spaces)}SettingsGoogleDrive.${method_name} result=${result}`);
        //     return result;
        // })


        console.log('Script loaded!');
    });


}, 10);

