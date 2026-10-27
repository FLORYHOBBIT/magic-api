export default {
    upload: {
        title: 'Upload Channel',
        name: 'Upload Channel',
        form: {
            protocol: 'Protocol',
            name: 'Name',
            description: 'Description',
            placeholder: {
                name: 'Referenced in scripts by uploader.job(..).channel("name")',
                description: 'Description',
                json: 'JSON object, e.g. {"X-Token":"abc"}'
            }
        },
        test: {
            start: 'Testing upload channel "{0}" (saved configuration)',
            success: 'Upload channel test succeeded in {0} ms',
            failure: 'Upload channel test failed: {0}'
        },
        tip: 'Passwords and secrets are shown as ENC(...) after saving. Leave unchanged to keep, retype to replace. Tests use the saved configuration.'
    }
}
